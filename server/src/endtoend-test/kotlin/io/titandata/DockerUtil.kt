/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata

import com.jcraft.jsch.JSch
import io.titandata.exception.CommandException
import io.titandata.util.CommandExecutor
import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Utility class for managing docker containers for integration tests. There are two types of
 * containers we care about: the titan server container and a remote SSH container. The titan
 * server is run on an alternate pool and port so as not to conflict with the running titan-server.
 * For the remote SSH server, we use 'rastasheep/ubuntu-sshd', which comes pre-built for remote access
 * over SSH.
 */
class DockerUtil(
    val identity: String = "test",
    val port: Int = 6001,
    val image: String = "titan:latest",
    val sshPort: Int = 6003
) {
    private val executor = CommandExecutor()
    private val retries = 60
    private val timeout = 1000L
    private val sshUser = "root"
    private val sshPassword = "root"
    val sshHost: String
        get() = InetAddress.getLocalHost().hostAddress

    fun url(path: String): String {
        return "http://localhost:$port/v1/$path"
    }

    companion object {
        val log = LoggerFactory.getLogger(CommandExecutor::class.java)
    }

    fun startTitan(entryPoint: String, daemon: Boolean): String {
        val args = mutableListOf("docker", "run", "--privileged", "--pid=host", "--network=host",
                "-v", "/var/lib:/var/lib", "-v", "/run/docker:/run/docker")
        if (daemon) {
            args.addAll(listOf("-d", "--restart", "always", "--name", "$identity-launch",
                    "-v", "/lib:/var/lib/$identity/system"))
        } else {
            args.addAll(listOf("--rm"))
        }
        args.addAll(listOf(
                "-v", "$identity-data:/var/lib/$identity/data",
                "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "-e", "TITAN_IDENTITY=$identity", "-e", "TITAN_IMAGE=$image",
                "-e", "TITAN_PORT=$port", image, "/bin/bash", "/titan/$entryPoint"

        ))
        return executor.exec(*args.toTypedArray()).trim()
    }

    fun startServer() {
        executor.exec("docker", "volume", "create", "$identity-data")

        startTitan("launch", true)
    }

    private fun testGet(): Int {
        try {
            val request = Request.Builder().url(url("repositories")).build()
            val response = OkHttpClient().newCall(request).execute()
            return response.code()
        } catch (e: Exception) {
            return 500
        }
    }

    fun waitForServer() {
        var tried = 1
        while (testGet() != 200) {
            if (tried++ == retries) {
                val process = executor.start("docker", "logs", "$identity-launch")
                log.error(process.inputStream.bufferedReader().readText())
                val stderr = process.errorStream.bufferedReader().readText()
                try {
                    throw Exception("Timed out waiting for server to start: " + stderr)
                } finally {
                    process.destroy()
                }
            }
            Thread.sleep(timeout)
        }
    }

    fun restartServer() {
        executor.exec("docker", "rm", "-f", "$identity-server")
    }

    fun stopServer(ignoreExceptions: Boolean = true) {
        try {
            executor.exec("docker", "rm", "-f", "$identity-launch")
        } catch (e: CommandException) {
            if (!ignoreExceptions) {
                throw e
            }
        }
        try {
            executor.exec("docker", "rm", "-f", "$identity-server")
        } catch (e: CommandException) {
            if (!ignoreExceptions) {
                throw e
            }
        }

        try {
            startTitan("teardown", false)
        } catch (e: CommandException) {
            if (!ignoreExceptions) {
                throw e
            }
        }

        try {
            executor.exec("docker", "volume", "rm", "$identity-data")
        } catch (e: CommandException) {
            if (!ignoreExceptions) {
                throw e
            }
        }
    }

    fun writeFile(volume: String, filename: String, content: String) {
        val path = "/var/lib/$identity/mnt/$volume/$filename"
        // Using 'docker cp' can mess with volume mounts, leave this as simple as possible
        executor.exec("docker", "exec", "$identity-server", "sh", "-c",
                "echo \"$content\" > $path")
    }

    fun readFile(volume: String, filename: String): String {
        val path = "/var/lib/$identity/mnt/$volume/$filename"
        return executor.exec("docker", "exec", "$identity-server", "cat", path)
    }

    fun pathExists(path: String): Boolean {
        try {
            executor.exec("docker", "exec", "$identity-server", "ls", path)
        } catch (e: CommandException) {
            return false
        }
        return true
    }

    fun writeFileSsh(path: String, content: String) {
        executor.exec("docker", "exec", "$identity-ssh", "sh", "-c",
                "echo \"$content\" > $path")
    }

    fun readFileSsh(path: String): String {
        return executor.exec("docker", "exec", "$identity-ssh", "cat", path)
    }

    fun mkdirSsh(path: String): String {
        return executor.exec("docker", "exec", "$identity-ssh", "mkdir", "-p", path)
    }

    fun startSsh() {
        executor.exec("docker", "run", "-p", "$sshPort:22", "-d", "--name", "$identity-ssh",
                "sshtest:latest")
    }

    fun testSsh(): Boolean {
        val jsch = JSch()
        try {
            val session = jsch.getSession(sshUser, "localhost", sshPort)
            session.setPassword(sshPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(timeout.toInt())
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun waitForSsh() {
        var tried = 1
        while (!testSsh()) {
            if (tried++ == retries) {
                throw Exception("Timed out waiting for SSH server to start")
            }
            Thread.sleep(timeout)
        }
    }

    fun stopSsh(ignoreExceptions: Boolean = true) {
        try {
            executor.exec("docker", "rm", "-f", "$identity-ssh")
        } catch (e: CommandException) {
            if (!ignoreExceptions) {
                throw e
            }
        }
    }

    fun getSshUri(): String {
        return "ssh://root:root@$sshHost:$sshPort"
    }
}
