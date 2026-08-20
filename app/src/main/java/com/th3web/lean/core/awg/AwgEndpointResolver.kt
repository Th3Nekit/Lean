package com.th3web.lean.core.awg

import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

fun interface AwgEndpointResolver {
    suspend fun resolve(host: String, port: Int): ResolvedAwgEndpoint
}

data class ResolvedAwgEndpoint(
    val endpoint: String,
    val networkToken: Any,
)

class SelectedNetworkEndpointResolver<N : Any>(
    private val networkProvider: suspend () -> N,
    private val lookup: suspend (N, String) -> List<InetAddress>,
    private val timeoutMs: Long,
) : AwgEndpointResolver {
    override suspend fun resolve(host: String, port: Int): ResolvedAwgEndpoint {
        require(host.isNotBlank()) { "Хост AmneziaWG не указан" }
        require(port in 1..65_535) { "Порт AmneziaWG вне диапазона" }
        require(timeoutMs > 0) { "Время ожидания физической сети должно быть положительным" }
        return try {
            withTimeout(timeoutMs) {
                val network = networkProvider()
                val addresses = lookup(network, host.trim())
                val selected = addresses.firstOrNull()
                    ?: throw AwgEndpointException("Адрес не найден в выбранной физической сети")
                val address = selected.hostAddress
                ResolvedAwgEndpoint(
                    endpoint = if (selected is Inet6Address) "[$address]:$port" else "$address:$port",
                    networkToken = network,
                )
            }
        } catch (failure: TimeoutCancellationException) {
            throw AwgEndpointException("Истекло время ожидания физической сети", failure)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: AwgEndpointException) {
            throw failure
        } catch (failure: Throwable) {
            throw AwgEndpointException("Не удалось разрешить адрес через физическую сеть", failure)
        }
    }
}

class AwgEndpointException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
