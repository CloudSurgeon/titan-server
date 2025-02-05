/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.client.infrastructure

import io.titandata.serialization.ModelTypeAdapters
import com.google.gson.GsonBuilder
import okhttp3.*
import java.io.File

open class ApiClient(val baseUrl: String) {
    protected val gson = ModelTypeAdapters.configure(GsonBuilder()).create()

    companion object {
        protected const val ContentType = "Content-Type"
        protected const val Accept = "Accept"
        protected const val JsonMediaType = "application/json"
        protected const val FormDataMediaType = "multipart/form-data"
        protected const val FormUrlEncMediaType = "application/x-www-form-urlencoded"
        protected const val XmlMediaType = "application/xml"

        @JvmStatic
        val client: OkHttpClient by lazy {
            builder.build()
        }

        @JvmStatic
        val builder: OkHttpClient.Builder = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
    }

    protected inline fun <reified T> requestBody(content: T, mediaType: String = JsonMediaType): RequestBody =
        when {
            content is File -> RequestBody.create(
                MediaType.parse(mediaType), content
            )
            mediaType == FormDataMediaType || mediaType == FormUrlEncMediaType -> {
                FormBody.Builder().apply {
                    // content's type *must* be Map<String, Any>
                    @Suppress("UNCHECKED_CAST")
                    (content as Map<String,String>).forEach { (key, value) ->
                        add(key, value)
                    }
                }.build()
            }
            mediaType == JsonMediaType -> RequestBody.create(
                MediaType.parse(mediaType), gson.toJson(content, T::class.java)
            )
            else -> throw NotImplementedError("requestBody only supports JSON body and File body")
        }

    protected inline fun <reified T: Any?> responseBody(body: ResponseBody?, mediaType: String? = JsonMediaType): T? {
        if(body == null) {
            return null
        }
        val bodyContent = body.string()
        if (bodyContent.isEmpty()) {
            return null
        }
        return when(mediaType) {
            JsonMediaType -> gson.fromJson(bodyContent, T::class.java)
            else -> throw NotImplementedError("responseBody currently only supports JSON body")
        }
    }

    protected inline fun <reified T: Any?> request(requestConfig: RequestConfig, body : Any? = null): ApiInfrastructureResponse<T?> {
        val httpUrl = HttpUrl.parse(baseUrl) ?: throw IllegalStateException("baseUrl is invalid.")

        val url = httpUrl.newBuilder()
            .addPathSegments(requestConfig.path.trimStart('/'))
            .apply {
                requestConfig.query.forEach { query ->
                    query.value.forEach { queryValue ->
                        addQueryParameter(query.key, queryValue)
                    }
                }
            }.build()

        // take content-type/accept from spec or set to default (application/json) if not defined
        if (requestConfig.headers[ContentType].isNullOrEmpty()) {
            requestConfig.headers[ContentType] = JsonMediaType
        }
        if (requestConfig.headers[Accept].isNullOrEmpty()) {
            requestConfig.headers[Accept] = JsonMediaType
        }
        val headers = requestConfig.headers

        if(headers[ContentType] ?: "" == "") {
            throw kotlin.IllegalStateException("Missing Content-Type header. This is required.")
        }

        if(headers[Accept] ?: "" == "") {
            throw kotlin.IllegalStateException("Missing Accept header. This is required.")
        }

        val contentType = (headers[ContentType] as String).substringBefore(";").toLowerCase()

        val request = when (requestConfig.method) {
            RequestMethod.DELETE -> Request.Builder().url(url).delete()
            RequestMethod.GET -> Request.Builder().url(url)
            RequestMethod.HEAD -> Request.Builder().url(url).head()
            RequestMethod.PATCH -> Request.Builder().url(url).patch(requestBody(body, contentType))
            RequestMethod.PUT -> Request.Builder().url(url).put(requestBody(body, contentType))
            RequestMethod.POST -> Request.Builder().url(url).post(requestBody(body, contentType))
            RequestMethod.OPTIONS -> Request.Builder().url(url).method("OPTIONS", null)
        }.apply {
            headers.forEach { header -> addHeader(header.key, header.value) }
        }.build()

        val response = client.newCall(request).execute()
        val accept = response.header(ContentType)?.substringBefore(";")?.toLowerCase()

        when {
            response.isRedirect -> return Redirection(
                    response.code(),
                    response.headers().toMultimap()
            )
            response.isInformational -> return Informational(
                    response.message(),
                    response.code(),
                    response.headers().toMultimap()
            )
            response.isSuccessful -> return Success(
                    responseBody(response.body(), accept),
                    response.code(),
                    response.headers().toMultimap()
            )
            response.isClientError -> return ClientError(
                    response.body()?.string(),
                    response.code(),
                    response.headers().toMultimap()
            )
            else -> return ServerError(
                    null,
                    response.body()?.string(),
                    response.code(),
                    response.headers().toMultimap()
            )
        }
    }
}
