@file:Suppress("unused")

package com.kidsync.app.data.repository

/**
 * DEPRECATED: FamilyRepositoryImpl has been replaced by [BucketRepositoryImpl].
 *
 * In the zero-knowledge architecture, the concept of "family" is a client-side
 * abstraction stored inside encrypted ops. The server only knows about anonymous
 * "buckets" -- opaque storage namespaces.
 *
 * @see BucketRepositoryImpl
 * @see com.kidsync.app.domain.repository.BucketRepository
 */
@Deprecated(
    message = "Use BucketRepositoryImpl instead. Family concept moved to client-side.",
    replaceWith = ReplaceWith("BucketRepositoryImpl")
)
typealias FamilyRepositoryImpl = BucketRepositoryImpl
