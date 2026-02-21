package dev.kidsync.server.services

import kotlinx.serialization.json.JsonObject

class ApiException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
    val details: JsonObject? = null,
) : Exception(message)
