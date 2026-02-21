@file:Suppress("unused")

package com.kidsync.app.domain.usecase.auth

/**
 * DEPRECATED: LoginUseCase has been replaced by [AuthenticateUseCase].
 *
 * In the zero-knowledge architecture there are no email/password logins.
 * Authentication is handled via Ed25519 challenge-response.
 *
 * @see AuthenticateUseCase
 */
@Deprecated(
    message = "Use AuthenticateUseCase instead. Email/password auth has been removed.",
    replaceWith = ReplaceWith("AuthenticateUseCase")
)
typealias LoginUseCase = AuthenticateUseCase
