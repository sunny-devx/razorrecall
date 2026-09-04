// ==============================================================================
// RazorRecall — Frontend Dashboard Controller (Vanilla JS / Browser Fetch API)
// ==============================================================================

(function () {
    "use strict";

    const API_BASE = "";
    const REFRESH_INTERVAL_MS = 4000;

    // DOM Elements
    const statusLabel = document.getElementById("status-label");
    const schedulerLabel = document.getElementById("scheduler-label");
    const valRecoveredAmount = document.getElementById("val-recovered-amount");
    const valRecoveredCases = document.getElementById("val-recovered-cases");
    const valRecoveryRate = document.getElementById("val-recovery-rate");
    const valAtRiskAmount = document.getElementById("val-at-risk-amount");
    const valAtRiskCases = document.getElementById("val-at-risk-cases");
    const valActiveCases = document.getElementById("val-active-cases");
    const valActiveDetails = document.getElementById("val-active-details");
    const casesTbody = document.getElementById("cases-tbody");
    const tableCount = document.getElementById("table-count");
    const btnRefresh = document.getElementById("btn-refresh");
    const btnRunScheduler = document.getElementById("btn-run-scheduler");
    const btnEvalDetected = document.getElementById("btn-eval-detected");
    const btnDispatchDue = document.getElementById("btn-dispatch-due");

    function formatCurrency(amount) {
        if (amount === null || amount === undefined) return "₹0.00";
        const num = parseFloat(amount);
        if (isNaN(num)) return "₹0.00";
        return "₹" + num.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function formatShortId(id) {
        if (!id) return "-";
        return id.substring(0, 8) + "...";
    }

    function formatDate(dateStr) {
        if (!dateStr) return "-";
        try {
            const d = new Date(dateStr);
            return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        } catch (e) {
            return dateStr;
        }
    }

    function getStatusBadge(status) {
        const s = (status || "").toUpperCase();
        let badgeClass = "badge-detected";
        if (s === "ACTION_PENDING") badgeClass = "badge-action_pending";
        else if (s === "WAITING_FOR_OUTCOME") badgeClass = "badge-waiting_for_outcome";
        else if (s === "RECOVERED") badgeClass = "badge-recovered";
        else if (s === "ABSTAINED") badgeClass = "badge-abstained";
        else if (s === "ESCALATED") badgeClass = "badge-escalated";
        else if (s === "ACTION_FAILED") badgeClass = "badge-failed";
        else if (s === "EXPIRED") badgeClass = "badge-expired";

        return `<span class="badge ${badgeClass}">${s}</span>`;
    }

    function getClassBadge(cls) {
        const c = (cls || "UNKNOWN").toUpperCase();
        let badgeClass = "badge-unknown";
        if (c === "SOFT") badgeClass = "badge-soft";
        else if (c === "HARD") badgeClass = "badge-hard";

        return `<span class="badge ${badgeClass}">${c}</span>`;
    }

    async function checkHealth() {
        try {
            const res = await fetch(`${API_BASE}/actuator/health`);
            if (res.ok) {
                statusLabel.textContent = "Connected (Server UP)";
                statusLabel.style.color = "#10b981";
            } else {
                statusLabel.textContent = `Server Status: HTTP ${res.status}`;
                statusLabel.style.color = "#f59e0b";
            }
        } catch (err) {
            statusLabel.textContent = "Disconnected";
            statusLabel.style.color = "#ef4444";
        }
    }

    async function checkSchedulerStatus() {
        if (!schedulerLabel) return;
        try {
            const res = await fetch(`${API_BASE}/api/recovery/scheduler/status`);
            if (res.ok) {
                const data = await res.json();
                schedulerLabel.textContent = `Scheduler: ${data.enabled ? "Active" : "Standby (Ready)"}`;
            }
        } catch (e) {
            schedulerLabel.textContent = "Scheduler: Standby";
        }
    }

    async function fetchMetrics() {
        try {
            const res = await fetch(`${API_BASE}/api/recovery/metrics`);
            if (!res.ok) return;
            const data = await res.json();

            valRecoveredAmount.textContent = formatCurrency(data.totalRecoveredAmount);
            valRecoveredCases.textContent = `${data.recoveredCases} of ${data.totalCases} cases recovered`;

            const rate = parseFloat(data.recoveryRatePercentage || 0).toFixed(1);
            valRecoveryRate.textContent = `${rate}%`;

            valAtRiskAmount.textContent = formatCurrency(data.totalAtRiskAmount);
            valAtRiskCases.textContent = `${data.totalCases} total payment failures`;

            const active = (data.actionPendingCases || 0) + (data.waitingForOutcomeCases || 0);
            valActiveCases.textContent = active;
            valActiveDetails.textContent = `${data.actionPendingCases || 0} pending / ${data.waitingForOutcomeCases || 0} awaiting outcome`;

        } catch (err) {
            console.error("Failed to fetch metrics:", err);
        }
    }

    async function fetchCases() {
        try {
            const res = await fetch(`${API_BASE}/api/recovery/cases`);
            if (!res.ok) return;
            const cases = await res.json();

            tableCount.textContent = `Showing ${cases.length} case${cases.length === 1 ? "" : "s"}`;

            if (!cases || cases.length === 0) {
                casesTbody.innerHTML = `<tr><td colspan="8" class="table-empty">No recovery cases yet. Run <code>./demo.sh</code> to simulate a payment failure!</td></tr>`;
                return;
            }

            // Sort cases by created time desc
            cases.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

            let html = "";
            for (const c of cases) {
                const caseIdShort = formatShortId(c.id);
                const orderId = c.orderId || "-";
                const paymentId = c.razorpayPaymentId || "-";
                const amount = formatCurrency(c.amount);
                const statusBadge = getStatusBadge(c.status);
                const classBadge = getClassBadge(c.failureClass);
                const created = formatDate(c.createdAt);

                let aiHtml = `<span class="ai-pending">Pending Evaluation</span>`;
                if (c.status !== "DETECTED") {
                    const strat = c.proposedStrategy || c.aiSuggestedStrategy || "ABSTAIN";
                    const conf = c.aiConfidence !== null && c.aiConfidence !== undefined ? c.aiConfidence : null;
                    const provider = c.aiProvider || (c.aiFallbackUsed ? "Fallback" : "Rule Engine");
                    const diag = c.aiDiagnosis || c.failureReason || "Failure diagnosis evaluated";

                    let confBadge = "";
                    if (conf !== null) {
                        const pct = Math.round(conf * 100);
                        let confClass = "confidence-high";
                        if (pct < 60) confClass = "confidence-low";
                        else if (pct < 85) confClass = "confidence-medium";
                        confBadge = `<span class="badge-confidence ${confClass}">${pct}% conf</span>`;
                    }

                    aiHtml = `
                        <div class="ai-cell">
                            <div class="ai-meta-line">
                                <span class="ai-strategy-tag">${strat}</span>
                                ${confBadge}
                                <span class="ai-provider-tag">${provider}</span>
                            </div>
                            <div class="ai-diagnosis-text" title="${diag}">${diag}</div>
                        </div>
                    `;
                }

                html += `
                    <tr>
                        <td class="mono" title="${c.id}">${caseIdShort}</td>
                        <td class="mono">${orderId}</td>
                        <td class="mono">${paymentId}</td>
                        <td style="font-weight:600;">${amount}</td>
                        <td>${statusBadge}</td>
                        <td>${classBadge}</td>
                        <td>${aiHtml}</td>
                        <td style="color:#94a3b8;">${created}</td>
                    </tr>
                `;
            }

            casesTbody.innerHTML = html;

        } catch (err) {
            console.error("Failed to fetch recovery cases:", err);
            casesTbody.innerHTML = `<tr><td colspan="8" class="table-empty" style="color:#ef4444;">Error loading recovery cases.</td></tr>`;
        }
    }

    async function refreshAll() {
        await Promise.all([
            checkHealth(),
            checkSchedulerStatus(),
            fetchMetrics(),
            fetchCases()
        ]);
    }

    // Button event listeners
    if (btnRefresh) {
        btnRefresh.addEventListener("click", () => {
            refreshAll();
        });
    }

    if (btnRunScheduler) {
        btnRunScheduler.addEventListener("click", async () => {
            btnRunScheduler.disabled = true;
            btnRunScheduler.textContent = "Running Cycle...";
            try {
                const res = await fetch(`${API_BASE}/api/recovery/scheduler/run`, { method: "POST" });
                if (res.ok) {
                    await refreshAll();
                }
            } catch (e) {
                console.error("Scheduler run error:", e);
            } finally {
                btnRunScheduler.disabled = false;
                btnRunScheduler.textContent = "Run Autonomous Cycle";
            }
        });
    }

    if (btnEvalDetected) {
        btnEvalDetected.addEventListener("click", async () => {
            btnEvalDetected.disabled = true;
            btnEvalDetected.textContent = "Evaluating...";
            try {
                const res = await fetch(`${API_BASE}/api/recovery/cases/evaluate-detected`, { method: "POST" });
                if (res.ok) {
                    await refreshAll();
                }
            } catch (e) {
                console.error(e);
            } finally {
                btnEvalDetected.disabled = false;
                btnEvalDetected.textContent = "Evaluate Detected";
            }
        });
    }

    if (btnDispatchDue) {
        btnDispatchDue.addEventListener("click", async () => {
            btnDispatchDue.disabled = true;
            btnDispatchDue.textContent = "Dispatching...";
            try {
                const res = await fetch(`${API_BASE}/api/recovery/cases/dispatch-due`, { method: "POST" });
                if (res.ok) {
                    await refreshAll();
                }
            } catch (e) {
                console.error(e);
            } finally {
                btnDispatchDue.disabled = false;
                btnDispatchDue.textContent = "Dispatch Due";
            }
        });
    }

    // Initial load
    refreshAll();

    // Periodic refresh
    setInterval(refreshAll, REFRESH_INTERVAL_MS);

})();
