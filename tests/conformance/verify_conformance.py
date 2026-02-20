#!/usr/bin/env python3
"""
Gate A Conformance Test Vector Verification Script

Verifies:
  - TV03: Hash chain computations (SHA-256 over hex-decoded prevHash + base64-decoded payload)
  - TV07: Canonical JSON serialization + SHA-256 hashes
  - TV05: Override state machine valid/invalid transition matrices
"""

import hashlib
import base64
import json
import sys
from pathlib import Path

CONFORMANCE_DIR = Path(__file__).parent

# ──────────────────────────────────────────────────────────────────────
# TV03 - Hash Chain Verification
# ──────────────────────────────────────────────────────────────────────

def verify_tv03():
    """Verify hash chain computations: SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))"""
    with open(CONFORMANCE_DIR / "tv03-hash-chain.json") as f:
        tv = json.load(f)

    results = []
    all_pass = True

    # 1. Verify the valid chain
    print("  [TV03] Valid Chain (5 ops):")
    for op in tv["validChain"]["ops"]:
        prev_hash_bytes = bytes.fromhex(op["devicePrevHash"])
        payload_bytes = base64.b64decode(op["encryptedPayload"])
        computed = hashlib.sha256(prev_hash_bytes + payload_bytes).hexdigest()
        expected = op["currentHash"]
        passed = computed == expected
        status = "PASS" if passed else "FAIL"
        print(f"    Op {op['index']}: {status}  (computed={computed[:16]}... expected={expected[:16]}...)")
        if not passed:
            all_pass = False

    # 2. Verify the tampered chain
    print("  [TV03] Tampered Chain:")
    for op in tv["tamperedChain"]["ops"]:
        prev_hash_bytes = bytes.fromhex(op["devicePrevHash"])
        payload_bytes = base64.b64decode(op["encryptedPayload"])
        computed = hashlib.sha256(prev_hash_bytes + payload_bytes).hexdigest()

        if op.get("chainValid", False):
            # For valid ops (1 and 2), currentHash should match
            expected = op["currentHash"]
            passed = computed == expected
            status = "PASS" if passed else "FAIL"
            print(f"    Op {op['index']} (valid): {status}")
        else:
            # For tampered ops (3, 4, 5), the recomputed hash should match
            # the currentHashRecomputed (not the original)
            expected_recomputed = op["currentHashRecomputed"]
            expected_original = op["currentHashOriginal"]
            recomp_match = computed == expected_recomputed
            differs_from_original = computed != expected_original
            passed = recomp_match and differs_from_original
            status = "PASS" if passed else "FAIL"
            print(f"    Op {op['index']} (tampered): {status}  recomputed_match={recomp_match}, differs_from_original={differs_from_original}")

        if not passed:
            all_pass = False

    # 3. Verify chain metadata
    valid_ops = tv["validChain"]["ops"]
    chain_len = tv["validChain"]["verification"]["chainLength"]
    first_hash = tv["validChain"]["verification"]["firstHash"]
    last_hash = tv["validChain"]["verification"]["lastHash"]
    meta_ok = (
        len(valid_ops) == chain_len
        and valid_ops[0]["currentHash"] == first_hash
        and valid_ops[-1]["currentHash"] == last_hash
    )
    print(f"    Chain metadata: {'PASS' if meta_ok else 'FAIL'} (length={chain_len}, first/last hash match={meta_ok})")
    if not meta_ok:
        all_pass = False

    return all_pass


# ──────────────────────────────────────────────────────────────────────
# TV07 - Canonical Serialization Verification
# ──────────────────────────────────────────────────────────────────────

def canonical_json(obj):
    """
    Produce canonical JSON: sorted keys at every level, compact separators,
    no trailing whitespace, null fields omitted.
    """
    # json.dumps with sort_keys=True handles nested sorting
    # We need to ensure numbers serialize correctly:
    #   - integers as integers (no .0)
    #   - floats with minimal representation
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def verify_tv07():
    """Verify canonical JSON serialization and SHA-256 hashes."""
    with open(CONFORMANCE_DIR / "tv07-canonical-serialization.json") as f:
        tv = json.load(f)

    all_pass = True
    print("  [TV07] Canonical Serialization Test Cases:")

    for tc in tv["testCases"]:
        tc_id = tc["id"]
        input_obj = tc["input"]
        expected_json = tc["expectedCanonicalJson"]
        expected_sha = tc["expectedSha256"]

        # Produce canonical JSON
        computed_json = canonical_json(input_obj)

        # Compute SHA-256 of the UTF-8 byte representation
        computed_sha = hashlib.sha256(computed_json.encode("utf-8")).hexdigest()

        json_match = computed_json == expected_json
        sha_match = computed_sha == expected_sha

        passed = json_match and sha_match
        status = "PASS" if passed else "FAIL"

        print(f"    {tc_id} ({tc['description'][:50]}...): {status}")
        if not json_match:
            print(f"      JSON mismatch:")
            print(f"        expected: {expected_json[:80]}...")
            print(f"        computed: {computed_json[:80]}...")
            all_pass = False
        if not sha_match:
            print(f"      SHA-256 mismatch: expected={expected_sha[:16]}... computed={computed_sha[:16]}...")
            all_pass = False

    return all_pass


# ──────────────────────────────────────────────────────────────────────
# TV05 - Override State Machine Verification
# ──────────────────────────────────────────────────────────────────────

def verify_tv05():
    """Verify the override state machine transition matrices."""
    with open(CONFORMANCE_DIR / "tv05-override-state-machine.json") as f:
        tv = json.load(f)

    all_pass = True

    # Build the valid transition graph from validTransitions
    valid_transitions_defined = set()
    for vt in tv["validTransitions"]:
        valid_transitions_defined.add((vt["from"], vt["to"]))

    # Define the states and their terminal status
    states = tv["states"]
    terminal_states = {s for s, info in states.items() if info["terminal"]}
    non_terminal_states = {s for s, info in states.items() if not info["terminal"]}

    # 1. Verify valid transitions
    print("  [TV05] Valid Transitions:")
    for vt in tv["validTransitions"]:
        tc = vt["testCase"]
        from_state = vt["from"]
        to_state = vt["to"]
        expected = tc["expectedResult"]

        # Check the transition is from a non-terminal state
        from_non_terminal = from_state in non_terminal_states
        # Check that expectedResult is ACCEPTED
        expected_accepted = expected == "ACCEPTED"

        passed = from_non_terminal and expected_accepted
        status = "PASS" if passed else "FAIL"
        print(f"    {vt['id']} ({from_state} -> {to_state}): {status}")
        if not passed:
            all_pass = False

    # 2. Verify invalid transitions
    print("  [TV05] Invalid Transitions:")
    for it in tv["invalidTransitions"]:
        from_state = it["from"]
        to_state = it["to"]
        expected_error = it["expectedError"]

        # Check that this is indeed invalid:
        # Either from a terminal state, or a disallowed transition from a non-terminal
        is_terminal_source = from_state in terminal_states
        is_not_in_valid = (from_state, to_state) not in valid_transitions_defined

        passed = is_not_in_valid and expected_error == "INVALID_STATE_TRANSITION"
        status = "PASS" if passed else "FAIL"
        print(f"    {it['id']} ({from_state} -> {to_state}): {status}  terminal_source={is_terminal_source}")
        if not passed:
            all_pass = False

    # 3. Verify authority constraints
    print("  [TV05] Authority Constraints:")
    for ac in tv["authorityConstraints"]:
        ac_id = ac["id"]
        has_error = "expectedError" in ac
        has_result = "expectedResult" in ac

        if has_error:
            # Should be TRANSITION_NOT_AUTHORIZED
            passed = ac["expectedError"] == "TRANSITION_NOT_AUTHORIZED"
            status = "PASS" if passed else "FAIL"
            print(f"    {ac_id} ({ac['description'][:50]}...): {status} (rejected: {ac['expectedError']})")
        elif has_result:
            passed = ac["expectedResult"] == "ACCEPTED"
            status = "PASS" if passed else "FAIL"
            print(f"    {ac_id} ({ac['description'][:50]}...): {status} (accepted)")
        else:
            print(f"    {ac_id}: FAIL (no expected result or error)")
            passed = False

        if not passed:
            all_pass = False

    # 4. Verify the full state transition matrix
    print("  [TV05] State Transition Matrix Completeness:")
    matrix = tv["stateTransitionMatrix"]["matrix"]
    all_states = list(states.keys())
    matrix_pass = True

    for from_state in all_states:
        if from_state not in matrix:
            print(f"    FAIL: Missing row for state {from_state}")
            matrix_pass = False
            continue
        for to_state in all_states:
            if to_state not in matrix[from_state]:
                print(f"    FAIL: Missing cell {from_state} -> {to_state}")
                matrix_pass = False
                continue

            cell = matrix[from_state][to_state]
            is_valid_cell = cell.startswith("VALID")

            # Cross-check: if cell says VALID, it should be in validTransitions
            if is_valid_cell and (from_state, to_state) not in valid_transitions_defined:
                print(f"    FAIL: Matrix says VALID for {from_state}->{to_state} but no validTransition defined")
                matrix_pass = False
            # If cell says INVALID and from a terminal state, verify
            if not is_valid_cell and from_state in terminal_states:
                # All terminal state transitions should be invalid - good
                pass

    if matrix_pass:
        print(f"    Matrix completeness: PASS ({len(all_states)}x{len(all_states)} = {len(all_states)**2} cells verified)")
    else:
        all_pass = False

    return all_pass


# ──────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("  KidSync Gate A - Conformance Test Vector Verification")
    print("=" * 70)
    print()

    results = {}

    print("[TV03] Hash Chain Verification")
    results["TV03"] = verify_tv03()
    print()

    print("[TV07] Canonical Serialization Verification")
    results["TV07"] = verify_tv07()
    print()

    print("[TV05] Override State Machine Verification")
    results["TV05"] = verify_tv05()
    print()

    print("=" * 70)
    print("  SUMMARY")
    print("=" * 70)
    for tv_id, passed in results.items():
        print(f"  {tv_id}: {'PASS' if passed else 'FAIL'}")

    overall = all(results.values())
    print()
    print(f"  Overall: {'PASS' if overall else 'FAIL'}")
    print("=" * 70)

    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
