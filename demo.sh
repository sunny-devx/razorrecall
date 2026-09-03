#!/usr/bin/env bash
# ==============================================================================
# RazorRecall — End-to-End Payment Recovery Lifecycle Demo Runner
# ==============================================================================
# Usage: ./demo.sh [BASE_URL] [SECRET]
# Default BASE_URL: http://localhost:8080
# Default SECRET:   test_webhook_secret_key_12345
# ==============================================================================

set -eo pipefail

BASE_URL="${1:-http://localhost:8080}"
SECRET="${2:-test_webhook_secret_key_12345}"

# ANSI Colors for terminal output
BOLD="\033[1m"
RESET="\033[0m"
GREEN="\033[32m"
BLUE="\033[34m"
CYAN="\033[36m"
YELLOW="\033[33m"
RED="\033[31m"
MAGENTA="\033[35m"

print_header() {
    echo -e "${CYAN}${BOLD}"
    echo "=============================================================================="
    echo "       RAZORRECALL — AUTONOMOUS PAYMENT RECOVERY INTELLIGENCE DEMO"
    echo "=============================================================================="
    echo -e "${RESET}"
    echo -e " Target Server : ${GREEN}${BASE_URL}${RESET}"
    echo -e " Webhook Secret: ${YELLOW}[CONFIGURED / SECURE]${RESET}"
    echo ""
}

fail() {
    echo -e "\n${RED}${BOLD}[ERROR] $1${RESET}" >&2
    exit 1
}

compute_hmac() {
    local payload="$1"
    printf "%s" "$payload" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //'
}

# ------------------------------------------------------------------------------
# STEP 0: Verify Connectivity
# ------------------------------------------------------------------------------
print_header

echo -e "${BLUE}${BOLD}▶ STEP 0: Checking Server Health...${RESET}"
HEALTH_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" || echo "000")
if [ "$HEALTH_HTTP" != "200" ]; then
    fail "Cannot connect to RazorRecall server at ${BASE_URL} (HTTP ${HEALTH_HTTP}).\nPlease ensure the application is running via: ./mvnw spring-boot:run"
fi
echo -e "${GREEN}✔ Server is UP and healthy (HTTP 200).${RESET}\n"

# ------------------------------------------------------------------------------
# STEP 1: Simulate Real Drop-Off Payment Failure
# ------------------------------------------------------------------------------
TIMESTAMP=$(date +%s)
PAY_ID="pay_demo_${TIMESTAMP}_${RANDOM}"
ORDER_ID="order_demo_${TIMESTAMP}_${RANDOM}"
AMOUNT_PAISE=250000 # 2500.00 INR

echo -e "${BLUE}${BOLD}▶ STEP 1: Ingesting payment.failed Webhook...${RESET}"
echo -e "  Payment ID: ${CYAN}${PAY_ID}${RESET}"
echo -e "  Order ID  : ${CYAN}${ORDER_ID}${RESET}"
echo -e "  Amount    : ${GREEN}₹2,500.00 INR${RESET} (${AMOUNT_PAISE} paise)"
echo -e "  Reason    : ${YELLOW}AUTHENTICATION_TIMEOUT (Checkout drop-off / 3DS timeout)${RESET}"

FAIL_PAYLOAD=$(cat <<EOF
{
  "event": "payment.failed",
  "payload": {
    "payment": {
      "entity": {
        "id": "${PAY_ID}",
        "order_id": "${ORDER_ID}",
        "amount": ${AMOUNT_PAISE},
        "currency": "INR",
        "status": "failed",
        "error_code": "AUTHENTICATION_TIMEOUT",
        "error_description": "Payment was dropped by user at checkout 3DS prompt",
        "error_reason": "AUTHENTICATION_TIMEOUT_USER_DROPPED"
      }
    }
  }
}
EOF
)

FAIL_SIG=$(compute_hmac "$FAIL_PAYLOAD")

FAIL_RESP=$(curl -s -X POST "${BASE_URL}/api/webhooks/razorpay" \
    -H "Content-Type: application/json" \
    -H "X-Razorpay-Signature: ${FAIL_SIG}" \
    -d "$FAIL_PAYLOAD")

# Extract recoveryCaseId from response
CASE_ID=$(echo "$FAIL_RESP" | grep -o '"recoveryCaseId":"[^"]*' | cut -d'"' -f4 || true)
if [ -z "$CASE_ID" ]; then
    fail "Webhook ingestion failed. Response: ${FAIL_RESP}"
fi

STATUS=$(echo "$FAIL_RESP" | grep -o '"recoveryStatus":"[^"]*' | cut -d'"' -f4 || echo "DETECTED")
CLASS=$(echo "$FAIL_RESP" | grep -o '"failureClass":"[^"]*' | cut -d'"' -f4 || echo "SOFT")

echo -e "${GREEN}✔ Webhook Verified & Processed (HMAC-SHA256 Valid).${RESET}"
echo -e "  Recovery Case Created: ${CYAN}${CASE_ID}${RESET}"
echo -e "  Initial Status       : ${MAGENTA}${STATUS}${RESET}"
echo -e "  Failure Classification: ${YELLOW}${CLASS}${RESET}\n"

# ------------------------------------------------------------------------------
# STEP 2: Evaluate Recovery Decision & Guardrails
# ------------------------------------------------------------------------------
echo -e "${BLUE}${BOLD}▶ STEP 2: Evaluating Failure & Guardrails...${RESET}"
EVAL_RESP=$(curl -s -X POST "${BASE_URL}/api/recovery/cases/${CASE_ID}/evaluate")

NEW_STATUS=$(echo "$EVAL_RESP" | grep -o '"status":"[^"]*' | cut -d'"' -f4 || true)
STRATEGY=$(echo "$EVAL_RESP" | grep -o '"proposedStrategy":"[^"]*' | cut -d'"' -f4 || true)
MESSAGE=$(echo "$EVAL_RESP" | grep -o '"message":"[^"]*' | cut -d'"' -f4 || true)

if [ "$NEW_STATUS" != "ACTION_PENDING" ]; then
    fail "Evaluation unexpected status: ${NEW_STATUS}. Response: ${EVAL_RESP}"
fi

echo -e "${GREEN}✔ Decision Engine & Guardrails Validated.${RESET}"
echo -e "  New Case Status  : ${MAGENTA}${NEW_STATUS}${RESET}"
echo -e "  Chosen Strategy  : ${CYAN}${STRATEGY}${RESET}"
echo -e "  Engine Message   : ${YELLOW}${MESSAGE}${RESET}\n"

# ------------------------------------------------------------------------------
# STEP 3: Dispatch Recovery Action via Gateway
# ------------------------------------------------------------------------------
echo -e "${BLUE}${BOLD}▶ STEP 3: Dispatching Recovery Action to Gateway...${RESET}"
DISPATCH_RESP=$(curl -s -X POST "${BASE_URL}/api/recovery/cases/${CASE_ID}/dispatch?force=true")

DISP_STATUS=$(echo "$DISPATCH_RESP" | grep -o '"targetStatus":"[^"]*' | cut -d'"' -f4 || true)
ACTION_URL=$(echo "$DISPATCH_RESP" | grep -o '"actionUrl":"[^"]*' | cut -d'"' -f4 || true)
ACTION_REF=$(echo "$DISPATCH_RESP" | grep -o '"actionReference":"[^"]*' | cut -d'"' -f4 || true)

if [ "$DISP_STATUS" != "WAITING_FOR_OUTCOME" ]; then
    fail "Dispatch unexpected status: ${DISP_STATUS}. Response: ${DISPATCH_RESP}"
fi

echo -e "${GREEN}✔ Recovery Action Successfully Dispatched.${RESET}"
echo -e "  Case Status     : ${MAGENTA}${DISP_STATUS}${RESET}"
echo -e "  Payment Link ID : ${CYAN}${ACTION_REF}${RESET}"
echo -e "  Payment Link URL: ${YELLOW}${ACTION_URL}${RESET}\n"

# ------------------------------------------------------------------------------
# STEP 4: Simulate Customer Payment & Reconcile payment.captured
# ------------------------------------------------------------------------------
CAPTURED_PAY_ID="pay_rec_${TIMESTAMP}_${RANDOM}"

echo -e "${BLUE}${BOLD}▶ STEP 4: Simulating Customer Recovery Payment (payment.captured)...${RESET}"
echo -e "  Captured Payment: ${CYAN}${CAPTURED_PAY_ID}${RESET}"
echo -e "  Order ID        : ${CYAN}${ORDER_ID}${RESET}"
echo -e "  Reference ID    : ${CYAN}${CASE_ID}${RESET}"

CAPTURE_PAYLOAD=$(cat <<EOF
{
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "${CAPTURED_PAY_ID}",
        "order_id": "${ORDER_ID}",
        "amount": ${AMOUNT_PAISE},
        "currency": "INR",
        "status": "captured",
        "notes": {
          "recovery_case_id": "${CASE_ID}",
          "reference_id": "${CASE_ID}"
        }
      }
    }
  }
}
EOF
)

CAPTURE_SIG=$(compute_hmac "$CAPTURE_PAYLOAD")

CAPTURE_RESP=$(curl -s -X POST "${BASE_URL}/api/webhooks/razorpay" \
    -H "Content-Type: application/json" \
    -H "X-Razorpay-Signature: ${CAPTURE_SIG}" \
    -d "$CAPTURE_PAYLOAD")

CAP_STATUS=$(echo "$CAPTURE_RESP" | grep -o '"status":"[^"]*' | cut -d'"' -f4 || true)
REC_STATUS=$(echo "$CAPTURE_RESP" | grep -o '"recoveryStatus":"[^"]*' | cut -d'"' -f4 || true)

if [ "$CAP_STATUS" != "RECOVERED" ] && [ "$REC_STATUS" != "RECOVERED" ]; then
    fail "Reconciliation did not mark case as RECOVERED. Response: ${CAPTURE_RESP}"
fi

echo -e "${GREEN}✔ Payment Captured Reconciled via 3-Tier Correlation (Priority 1: Order ID / Priority 2: Reference ID).${RESET}"
echo -e "  Final Case Status: ${GREEN}${BOLD}RECOVERED${RESET}\n"

# ------------------------------------------------------------------------------
# STEP 5: Live Recovery Metrics
# ------------------------------------------------------------------------------
echo -e "${BLUE}${BOLD}▶ STEP 5: Querying Live Metrics (GET /api/recovery/metrics)...${RESET}"
METRICS_JSON=$(curl -s "${BASE_URL}/api/recovery/metrics")

TOTAL_CASES=$(echo "$METRICS_JSON" | grep -o '"totalCases":[0-9]*' | cut -d':' -f2 || echo "0")
RECOVERED_CASES=$(echo "$METRICS_JSON" | grep -o '"recoveredCases":[0-9]*' | cut -d':' -f2 || echo "0")
RECOVERED_AMOUNT=$(echo "$METRICS_JSON" | grep -o '"totalRecoveredAmount":[0-9.]*' | cut -d':' -f2 || echo "0.00")
RECOVERY_RATE=$(echo "$METRICS_JSON" | grep -o '"recoveryRatePercentage":[0-9.]*' | cut -d':' -f2 || echo "0.00")

echo -e "${CYAN}------------------------------------------------------------------------------${RESET}"
echo -e " ${BOLD}RECOVERY METRICS SNAPSHOT:${RESET}"
echo -e "   Total Recovery Cases   : ${BOLD}${TOTAL_CASES}${RESET}"
echo -e "   Successfully Recovered : ${GREEN}${BOLD}${RECOVERED_CASES}${RESET}"
echo -e "   Total Recovered Revenue: ${GREEN}${BOLD}₹${RECOVERED_AMOUNT} INR${RESET}"
echo -e "   Overall Recovery Rate  : ${CYAN}${BOLD}${RECOVERY_RATE}%${RESET}"
echo -e "${CYAN}------------------------------------------------------------------------------${RESET}\n"

# ------------------------------------------------------------------------------
# SUMMARY
# ------------------------------------------------------------------------------
echo -e "${GREEN}${BOLD}=============================================================================="
echo "                     DEMO LIFECYCLE COMPLETED SUCCESSFULLY!"
echo "=============================================================================="
echo -e "${RESET}"
echo -e " Lifecycle Progression:"
echo -e "   1. ${RED}PAYMENT_FAILED${RESET} (Checkout drop-off recorded)"
echo -e "   2. ${MAGENTA}CASE DETECTED${RESET} (Classification: SOFT)"
echo -e "   3. ${YELLOW}GUARDRAILS PASSED${RESET} (Threshold <= ₹5,00,000, Policy validated)"
echo -e "   4. ${CYAN}ACTION DISPATCHED${RESET} (Razorpay Payment Link generated)"
echo -e "   5. ${BLUE}PAYMENT CAPTURED${RESET} (Reconciled via webhook)"
echo -e "   6. ${GREEN}${BOLD}RECOVERED${RESET} (₹2,500.00 recovered to merchant revenue)"
echo ""
echo -e " View live visual updates on the dashboard: ${CYAN}${BOLD}${BASE_URL}/${RESET}\n"
