@file:Suppress("unused")

package com.kidsync.app.domain.repository

/**
 * DEPRECATED: FamilyRepository has been replaced by [BucketRepository].
 *
 * In the zero-knowledge architecture, the concept of "family" is a client-side
 * abstraction stored inside encrypted ops. The server only knows about anonymous
 * "buckets" -- opaque storage namespaces.
 *
 * @see BucketRepository
 */
@Deprecated(
    message = "Use BucketRepository instead. Family concept moved to client-side.",
    replaceWith = ReplaceWith("BucketRepository")
)
typealias FamilyRepository = BucketRepository
