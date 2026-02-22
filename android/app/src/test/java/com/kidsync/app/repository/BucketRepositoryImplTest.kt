package com.kidsync.app.repository

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.dao.KeyAttestationDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.data.local.entity.KeyAttestationEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.*
import com.kidsync.app.data.repository.BucketRepositoryImpl
import com.kidsync.app.domain.model.KeyAttestation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

/**
 * Tests for BucketRepositoryImpl covering:
 *
 * 1. Atomic bucket list writes (SEC6-A-09)
 * 2. storeLocalBucketName atomic write of both name and bucket set
 * 3. getAccessibleBuckets returns stored list
 * 4. createBucket stores in DAO and prefs
 * 5. deleteBucket cleans up DAO
 * 6. joinBucket calls API
 * 7. leaveBucket calls API and cleans up
 * 8. getBucketDevices maps DTOs correctly
 * 9. uploadKeyAttestation stores locally
 * 10. getKeyAttestations falls back to local cache on API failure
 * 11. getLocalBucketName returns stored name
 * 12. saveBucket delegates to DAO
 * 13. createInvite hashes the token
 */
class BucketRepositoryImplTest : FunSpec({

    val apiService = mockk<ApiService>()
    val bucketDao = mockk<BucketDao>()
    val keyAttestationDao = mockk<KeyAttestationDao>()
    val cryptoManager = mockk<CryptoManager>()
    val encryptedPrefs = mockk<SharedPreferences>()
    val editor = mockk<SharedPreferences.Editor>()

    fun createRepo() = BucketRepositoryImpl(
        apiService = apiService,
        bucketDao = bucketDao,
        keyAttestationDao = keyAttestationDao,
        cryptoManager = cryptoManager,
        encryptedPrefs = encryptedPrefs
    )

    beforeEach {
        clearAllMocks()

        every { encryptedPrefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns true
        every { editor.apply() } just Runs
    }

    // ── storeLocalBucketName (SEC6-A-09) ─────────────────────────────────────

    test("storeLocalBucketName writes name and bucket set atomically") {
        every {
            encryptedPrefs.getStringSet("accessible_bucket_ids", emptySet())
        } returns setOf("bucket-existing")

        val repo = createRepo()
        repo.storeLocalBucketName("bucket-new", "My Family")

        verify {
            editor.putString("bucket_name_bucket-new", "My Family")
            editor.putStringSet("accessible_bucket_ids", match {
                it.contains("bucket-existing") && it.contains("bucket-new")
            })
            editor.commit()
        }
    }

    test("storeLocalBucketName with empty existing set") {
        every {
            encryptedPrefs.getStringSet("accessible_bucket_ids", emptySet())
        } returns emptySet()

        val repo = createRepo()
        repo.storeLocalBucketName("bucket-first", "First Family")

        verify {
            editor.putString("bucket_name_bucket-first", "First Family")
            editor.putStringSet("accessible_bucket_ids", setOf("bucket-first"))
            editor.commit()
        }
    }

    // ── getLocalBucketName ───────────────────────────────────────────────────

    test("getLocalBucketName returns stored name") {
        every {
            encryptedPrefs.getString("bucket_name_bucket-123", null)
        } returns "My Family Bucket"

        val repo = createRepo()
        repo.getLocalBucketName("bucket-123") shouldBe "My Family Bucket"
    }

    test("getLocalBucketName returns null when no name stored") {
        every {
            encryptedPrefs.getString("bucket_name_bucket-xyz", null)
        } returns null

        val repo = createRepo()
        repo.getLocalBucketName("bucket-xyz") shouldBe null
    }

    // ── getAccessibleBuckets ─────────────────────────────────────────────────

    test("getAccessibleBuckets returns stored list") {
        every {
            encryptedPrefs.getStringSet("accessible_bucket_ids", emptySet())
        } returns setOf("b1", "b2", "b3")

        val repo = createRepo()
        val buckets = repo.getAccessibleBuckets()
        buckets.toSet() shouldBe setOf("b1", "b2", "b3")
    }

    test("getAccessibleBuckets returns empty when nothing stored") {
        every {
            encryptedPrefs.getStringSet("accessible_bucket_ids", emptySet())
        } returns emptySet()

        val repo = createRepo()
        repo.getAccessibleBuckets() shouldBe emptyList()
    }

    // ── createBucket ─────────────────────────────────────────────────────────

    test("createBucket stores in DAO and adds to accessible buckets") {
        coEvery { apiService.createBucket() } returns BucketResponse("bucket-new")
        coEvery { bucketDao.insertBucket(any()) } just Runs
        every {
            encryptedPrefs.getStringSet("accessible_bucket_ids", emptySet())
        } returns emptySet()

        val repo = createRepo()
        val result = repo.createBucket()

        result.isSuccess shouldBe true
        result.getOrNull()!!.bucketId shouldBe "bucket-new"

        coVerify { bucketDao.insertBucket(match { it.bucketId == "bucket-new" }) }
        verify {
            editor.putStringSet("accessible_bucket_ids", setOf("bucket-new"))
            editor.commit()
        }
    }

    test("createBucket returns failure on API error") {
        coEvery { apiService.createBucket() } throws RuntimeException("Server error")

        val repo = createRepo()
        val result = repo.createBucket()

        result.isFailure shouldBe true
    }

    // ── deleteBucket ─────────────────────────────────────────────────────────

    test("deleteBucket calls API and removes from DAO") {
        coEvery { apiService.deleteBucket("bucket-del") } just Runs
        coEvery { bucketDao.deleteBucket("bucket-del") } just Runs

        val repo = createRepo()
        val result = repo.deleteBucket("bucket-del")

        result.isSuccess shouldBe true
        coVerify { apiService.deleteBucket("bucket-del") }
        coVerify { bucketDao.deleteBucket("bucket-del") }
    }

    test("deleteBucket returns failure on API error") {
        coEvery { apiService.deleteBucket(any()) } throws RuntimeException("Unauthorized")

        val repo = createRepo()
        val result = repo.deleteBucket("bucket-fail")

        result.isFailure shouldBe true
    }

    // ── joinBucket ───────────────────────────────────────────────────────────

    test("joinBucket calls API with invite token") {
        coEvery { apiService.joinBucket("bucket-join", any()) } just Runs

        val repo = createRepo()
        val result = repo.joinBucket("bucket-join", "invite-token-123")

        result.isSuccess shouldBe true
        coVerify {
            apiService.joinBucket("bucket-join", match { it.inviteToken == "invite-token-123" })
        }
    }

    // ── leaveBucket ──────────────────────────────────────────────────────────

    test("leaveBucket calls API and removes from DAO") {
        coEvery { apiService.leaveBucket("bucket-leave") } just Runs
        coEvery { bucketDao.deleteBucket("bucket-leave") } just Runs

        val repo = createRepo()
        val result = repo.leaveBucket("bucket-leave")

        result.isSuccess shouldBe true
        coVerify { apiService.leaveBucket("bucket-leave") }
        coVerify { bucketDao.deleteBucket("bucket-leave") }
    }

    // ── createInvite ─────────────────────────────────────────────────────────

    test("createInvite hashes the token before sending") {
        every { cryptoManager.sha256Hex("raw-invite-token") } returns "abcdef1234567890"
        val response = mockk<retrofit2.Response<Unit>>()
        every { response.isSuccessful } returns true
        coEvery { apiService.createInvite("bucket-inv", any()) } returns response

        val repo = createRepo()
        val result = repo.createInvite("bucket-inv", "raw-invite-token")

        result.isSuccess shouldBe true
        coVerify {
            apiService.createInvite("bucket-inv", match { it.tokenHash == "abcdef1234567890" })
        }
    }

    test("createInvite returns failure when API response is not successful") {
        every { cryptoManager.sha256Hex(any()) } returns "hash"
        val response = mockk<retrofit2.Response<Unit>>()
        every { response.isSuccessful } returns false
        every { response.code() } returns 409
        every { response.message() } returns "Conflict"
        coEvery { apiService.createInvite(any(), any()) } returns response

        val repo = createRepo()
        val result = repo.createInvite("bucket-inv", "token")

        result.isFailure shouldBe true
    }

    // ── getBucketDevices ─────────────────────────────────────────────────────

    test("getBucketDevices maps DTOs to domain models") {
        coEvery { apiService.getBucketDevices("bucket-dev") } returns BucketDevicesResponse(
            devices = listOf(
                DeviceInfo(
                    deviceId = "device-1",
                    signingKey = "sk1",
                    encryptionKey = "ek1",
                    grantedAt = "2026-01-01T00:00:00Z"
                ),
                DeviceInfo(
                    deviceId = "device-2",
                    signingKey = "sk2",
                    encryptionKey = "ek2",
                    grantedAt = "2026-01-02T00:00:00Z"
                )
            )
        )

        val repo = createRepo()
        val result = repo.getBucketDevices("bucket-dev")

        result.isSuccess shouldBe true
        val devices = result.getOrNull()!!
        devices.size shouldBe 2
        devices[0].deviceId shouldBe "device-1"
        devices[0].encryptionKey shouldBe "ek1"
        devices[1].deviceId shouldBe "device-2"
    }

    // ── getBucket ─────────────────────────────────────────────────────────────

    test("getBucket returns domain model when found") {
        coEvery { bucketDao.getBucket("bucket-get") } returns BucketEntity(
            bucketId = "bucket-get",
            createdByDeviceId = "device-creator",
            createdAt = "2026-01-01T00:00:00Z"
        )

        val repo = createRepo()
        val bucket = repo.getBucket("bucket-get")

        bucket shouldNotBe null
        bucket!!.bucketId shouldBe "bucket-get"
        bucket.createdByDeviceId shouldBe "device-creator"
    }

    test("getBucket returns null when not found") {
        coEvery { bucketDao.getBucket("bucket-missing") } returns null

        val repo = createRepo()
        repo.getBucket("bucket-missing") shouldBe null
    }

    // ── saveBucket ───────────────────────────────────────────────────────────

    test("saveBucket delegates to DAO") {
        coEvery { bucketDao.insertBucket(any()) } just Runs

        val repo = createRepo()
        repo.saveBucket(
            com.kidsync.app.domain.model.Bucket(
                bucketId = "bucket-save",
                createdByDeviceId = "device",
                createdAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        )

        coVerify {
            bucketDao.insertBucket(match { it.bucketId == "bucket-save" })
        }
    }

    // ── uploadKeyAttestation ─────────────────────────────────────────────────

    test("uploadKeyAttestation uploads to API and caches locally") {
        coEvery { apiService.uploadAttestation(any()) } just Runs
        coEvery { keyAttestationDao.insertAttestation(any()) } just Runs

        val attestation = KeyAttestation(
            signerDeviceId = "signer-1",
            attestedDeviceId = "attested-1",
            attestedEncryptionKey = "enc-key",
            signature = "sig-data",
            createdAt = "2026-01-01T00:00:00Z"
        )

        val repo = createRepo()
        val result = repo.uploadKeyAttestation(attestation)

        result.isSuccess shouldBe true
        coVerify { apiService.uploadAttestation(any()) }
        coVerify {
            keyAttestationDao.insertAttestation(match {
                it.signerDeviceId == "signer-1" && it.attestedDeviceId == "attested-1"
            })
        }
    }

    // ── getKeyAttestations with fallback ─────────────────────────────────────

    test("getKeyAttestations returns remote data on success") {
        coEvery { apiService.getAttestations("device-att") } returns listOf(
            AttestationResponse(
                signerDeviceId = "signer",
                attestedDeviceId = "device-att",
                attestedKey = "key",
                signature = "sig",
                createdAt = "2026-01-01T00:00:00Z"
            )
        )
        coEvery { keyAttestationDao.insertAttestations(any()) } just Runs

        val repo = createRepo()
        val result = repo.getKeyAttestations("device-att")

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 1
        result.getOrNull()!![0].attestedDeviceId shouldBe "device-att"
    }

    test("getKeyAttestations falls back to local cache on API failure") {
        coEvery { apiService.getAttestations("device-att") } throws RuntimeException("offline")
        coEvery { keyAttestationDao.getAttestationsForDevice("device-att") } returns listOf(
            KeyAttestationEntity(
                signerDeviceId = "cached-signer",
                attestedDeviceId = "device-att",
                attestedEncryptionKey = "cached-key",
                signature = "cached-sig",
                createdAt = "2026-01-01T00:00:00Z"
            )
        )

        val repo = createRepo()
        val result = repo.getKeyAttestations("device-att")

        result.isSuccess shouldBe true
        result.getOrNull()!![0].signerDeviceId shouldBe "cached-signer"
    }

    // ── observeBuckets ───────────────────────────────────────────────────────

    test("observeBuckets maps entities to domain models") {
        every { bucketDao.observeAllBuckets() } returns flowOf(
            listOf(
                BucketEntity("b1", "d1", "2026-01-01T00:00:00Z"),
                BucketEntity("b2", "d2", "2026-01-02T00:00:00Z")
            )
        )

        val repo = createRepo()
        val buckets = repo.observeBuckets().first()

        buckets.size shouldBe 2
        buckets[0].bucketId shouldBe "b1"
        buckets[1].bucketId shouldBe "b2"
    }
})
