/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.serialization

import io.titandata.models.Remote
import io.titandata.models.RemoteParameters
import io.titandata.serialization.remote.EngineRemoteUtil
import io.titandata.serialization.remote.NopRemoteUtil
import io.titandata.serialization.remote.S3RemoteUtil
import io.titandata.serialization.remote.SshRemoteUtil
import java.net.URI
import java.net.URISyntaxException

class RemoteUtil {

    private val remoteUtil = mapOf(
        "nop" to NopRemoteUtil(),
        "ssh" to SshRemoteUtil(),
        "engine" to EngineRemoteUtil(),
        "s3" to S3RemoteUtil()
    )

    fun parseUri(uriString: String, name: String, properties: Map<String, String>) : Remote {
        try {
            val uri = URI(uriString)

            val provider = uri.scheme ?: uriString

            if (uri.query != null || uri.fragment != null) {
                throw IllegalArgumentException("Malformed remote identifier, query and fragments are not allowed")
            }

            return remoteUtil[provider]?.parseUri(uri, name, properties) ?:
                throw IllegalArgumentException("Unknown remote provider or malformed remote identifier '$provider'")

        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Invalid URI syntax", e)
        }
    }

    fun toUri(remote: Remote) : Pair<String, Map<String, String>> {
        return remoteUtil[remote.provider]?.toUri(remote) ?:
                throw IllegalArgumentException("Unknown remote provider '${remote.provider}")
    }

    fun getParameters(remote: Remote) : RemoteParameters {
        return remoteUtil[remote.provider]?.getParameters(remote) ?:
            throw IllegalArgumentException("Unknown remote provider '${remote.provider}")
    }
}
