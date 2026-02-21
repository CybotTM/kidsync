@file:Suppress("unused")

package com.kidsync.app.domain.usecase.auth

/**
 * DEPRECATED: RefreshTokenUseCase has been removed.
 *
 * In the zero-knowledge architecture there are no refresh tokens.
 * When the session expires, the client re-authenticates via
 * Ed25519 challenge-response using [AuthenticateUseCase].
 *
 * @see AuthenticateUseCase
 */
@Deprecated(
    message = "Use AuthenticateUseCase instead. Refresh tokens have been removed.",
    replaceWith = ReplaceWith("AuthenticateUseCase")
)
typealias RefreshTokenUseCase = AuthenticateUseCase
