package dev.kidsync.server

import dev.kidsync.server.util.ValidationUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for ValidationUtil - no server, no database.
 */
class ValidationUtilTest {

    // ================================================================
    // isValidUUID
    // ================================================================

    @Test
    fun `valid UUID lowercase`() {
        assertTrue(ValidationUtil.isValidUUID("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `valid UUID uppercase`() {
        assertTrue(ValidationUtil.isValidUUID("550E8400-E29B-41D4-A716-446655440000"))
    }

    @Test
    fun `valid UUID mixed case`() {
        assertTrue(ValidationUtil.isValidUUID("550e8400-E29B-41d4-A716-446655440000"))
    }

    @Test
    fun `valid UUID all zeros`() {
        assertTrue(ValidationUtil.isValidUUID("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `valid UUID all f`() {
        assertTrue(ValidationUtil.isValidUUID("ffffffff-ffff-ffff-ffff-ffffffffffff"))
    }

    @Test
    fun `invalid UUID empty string`() {
        assertFalse(ValidationUtil.isValidUUID(""))
    }

    @Test
    fun `invalid UUID too short`() {
        assertFalse(ValidationUtil.isValidUUID("550e8400-e29b-41d4-a716"))
    }

    @Test
    fun `invalid UUID too long`() {
        assertFalse(ValidationUtil.isValidUUID("550e8400-e29b-41d4-a716-4466554400001"))
    }

    @Test
    fun `invalid UUID missing hyphens`() {
        assertFalse(ValidationUtil.isValidUUID("550e8400e29b41d4a716446655440000"))
    }

    @Test
    fun `invalid UUID wrong characters`() {
        assertFalse(ValidationUtil.isValidUUID("550e8400-e29b-41d4-a716-44665544000g"))
    }

    @Test
    fun `invalid UUID extra hyphens`() {
        assertFalse(ValidationUtil.isValidUUID("550e-8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `invalid UUID hyphen in wrong position`() {
        assertFalse(ValidationUtil.isValidUUID("550e84-00-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `invalid UUID with spaces`() {
        assertFalse(ValidationUtil.isValidUUID("550e8400 e29b 41d4 a716 446655440000"))
    }

    @Test
    fun `invalid UUID with braces`() {
        assertFalse(ValidationUtil.isValidUUID("{550e8400-e29b-41d4-a716-446655440000}"))
    }

    // ================================================================
    // isValidSha256Hex
    // ================================================================

    @Test
    fun `valid SHA-256 hex`() {
        assertTrue(ValidationUtil.isValidSha256Hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }

    @Test
    fun `valid SHA-256 hex all zeros`() {
        assertTrue(ValidationUtil.isValidSha256Hex("0".repeat(64)))
    }

    @Test
    fun `invalid SHA-256 hex uppercase rejected`() {
        assertFalse(ValidationUtil.isValidSha256Hex("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"))
    }

    @Test
    fun `invalid SHA-256 hex too short`() {
        assertFalse(ValidationUtil.isValidSha256Hex("e3b0c44298fc1c149afbf4c8996fb924"))
    }

    @Test
    fun `invalid SHA-256 hex too long`() {
        assertFalse(ValidationUtil.isValidSha256Hex("0".repeat(65)))
    }

    @Test
    fun `invalid SHA-256 hex empty`() {
        assertFalse(ValidationUtil.isValidSha256Hex(""))
    }

    @Test
    fun `invalid SHA-256 hex with invalid char`() {
        assertFalse(ValidationUtil.isValidSha256Hex("g".repeat(64)))
    }

    // ================================================================
    // isValidBase64
    // ================================================================

    @Test
    fun `valid base64 standard`() {
        assertTrue(ValidationUtil.isValidBase64("aGVsbG8gd29ybGQ="))
    }

    @Test
    fun `valid base64 no padding`() {
        assertTrue(ValidationUtil.isValidBase64("aGVsbG8"))
    }

    @Test
    fun `valid base64 with plus and slash`() {
        assertTrue(ValidationUtil.isValidBase64("abc+def/ghi="))
    }

    @Test
    fun `invalid base64 empty string`() {
        assertFalse(ValidationUtil.isValidBase64(""))
    }

    @Test
    fun `invalid base64 blank string`() {
        assertFalse(ValidationUtil.isValidBase64("   "))
    }

    @Test
    fun `invalid base64 with special chars`() {
        assertFalse(ValidationUtil.isValidBase64("abc!def"))
    }

    // ================================================================
    // isValidBase64Url
    // ================================================================

    @Test
    fun `valid base64url with underscore and hyphen`() {
        assertTrue(ValidationUtil.isValidBase64Url("abc_def-ghi"))
    }

    @Test
    fun `valid base64url with padding`() {
        assertTrue(ValidationUtil.isValidBase64Url("abc_def=="))
    }

    @Test
    fun `invalid base64url empty`() {
        assertFalse(ValidationUtil.isValidBase64Url(""))
    }

    @Test
    fun `invalid base64url blank`() {
        assertFalse(ValidationUtil.isValidBase64Url("   "))
    }

    @Test
    fun `invalid base64url with plus sign`() {
        // base64url uses - not +
        assertFalse(ValidationUtil.isValidBase64Url("abc+def"))
    }

    // ================================================================
    // isValidPublicKey
    // ================================================================

    @Test
    fun `valid public key short string`() {
        assertTrue(ValidationUtil.isValidPublicKey("MCowBQYDK2VuAyEA"))
    }

    @Test
    fun `valid public key max length`() {
        assertTrue(ValidationUtil.isValidPublicKey("a".repeat(1024)))
    }

    @Test
    fun `invalid public key empty`() {
        assertFalse(ValidationUtil.isValidPublicKey(""))
    }

    @Test
    fun `invalid public key blank`() {
        assertFalse(ValidationUtil.isValidPublicKey("   "))
    }

    @Test
    fun `invalid public key too long`() {
        assertFalse(ValidationUtil.isValidPublicKey("a".repeat(1025)))
    }

    // ================================================================
    // isValidPlatform
    // ================================================================

    @Test
    fun `valid platform FCM`() {
        assertTrue(ValidationUtil.isValidPlatform("FCM"))
    }

    @Test
    fun `valid platform APNS`() {
        assertTrue(ValidationUtil.isValidPlatform("APNS"))
    }

    @Test
    fun `invalid platform lowercase`() {
        assertFalse(ValidationUtil.isValidPlatform("fcm"))
    }

    @Test
    fun `invalid platform unknown`() {
        assertFalse(ValidationUtil.isValidPlatform("GCM"))
    }

    @Test
    fun `invalid platform empty`() {
        assertFalse(ValidationUtil.isValidPlatform(""))
    }

    // ================================================================
    // isNonBlankWithMaxLength
    // ================================================================

    @Test
    fun `valid non-blank within limit`() {
        assertTrue(ValidationUtil.isNonBlankWithMaxLength("hello", 10))
    }

    @Test
    fun `valid non-blank exactly at limit`() {
        assertTrue(ValidationUtil.isNonBlankWithMaxLength("12345", 5))
    }

    @Test
    fun `invalid blank string`() {
        assertFalse(ValidationUtil.isNonBlankWithMaxLength("", 10))
    }

    @Test
    fun `invalid whitespace only`() {
        assertFalse(ValidationUtil.isNonBlankWithMaxLength("   ", 10))
    }

    @Test
    fun `invalid exceeds max length`() {
        assertFalse(ValidationUtil.isNonBlankWithMaxLength("123456", 5))
    }
}
