#include "port_selector.h"
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "PortMawer::Selector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

PortSelector::PortSelector() {
    ports_to_test_ = {443, 8443, 4443, 2053, 2083, 2087, 2096, 8080, 8888, 9443, 44321, 10443};
}

PortSelector::~PortSelector() { stopScan(); }

void PortSelector::setTargetHost(const std::string& host) { target_host_ = host; }
void PortSelector::setPortRange(int start, int end) {
    ports_to_test_.clear();
    for (int p = start; p <= end; p++) ports_to_test_.push_back(p);
}
void PortSelector::addCustomPort(int port) { ports_to_test_.push_back(port); }
void PortSelector::setTimeout(int ms) { timeout_ms_ = ms; }

void PortSelector::startScan(std::function<void(int, TestResult)> onResult) {
    if (scanning_.load()) return;
    scanning_ = true;
    scan_thread_ = std::thread(&PortSelector::scanWorker, this, onResult);
}

void PortSelector::stopScan() {
    scanning_ = false;
    if (scan_thread_.joinable()) scan_thread_.join();
}

bool PortSelector::isScanning() const { return scanning_.load(); }

void PortSelector::scanWorker(std::function<void(int, TestResult)> onResult) {
    LOGI("Starting port scan on host: %s", target_host_.c_str());
    
    // Clear previous results under lock
    {
        std::lock_guard<std::mutex> lock(results_mutex_);
        results_.clear();
    }

    for (size_t i = 0; i < ports_to_test_.size() && scanning_.load(); i++) {
        int port = ports_to_test_[i];
        TestResult result = ConnectionTester::testPortWithDomain(target_host_, port, timeout_ms_);

        PortCandidate candidate;
        candidate.port = port;
        candidate.latency_ms = result.latency_ms;
        candidate.is_active = result.success;
        candidate.consecutive_failures = result.success ? 0 : 1;
        candidate.total_uses = 0;

        // FIX: Push directly to the member vector under a single, short lock
        {
            std::lock_guard<std::mutex> lock(results_mutex_);
            results_.push_back(candidate);
        }

        if (onResult) onResult(port, result);
        LOGI("Tested port %d: %s (%dms)", port, result.success ? "OK" : "FAIL", result.latency_ms);
    }

    // Sort once at the end: Active ports first, then by lowest latency
    {
        std::lock_guard<std::mutex> lock(results_mutex_);
        std::sort(results_.begin(), results_.end(), [](const PortCandidate& a, const PortCandidate& b) {
            if (a.is_active != b.is_active) return a.is_active > b.is_active; // true (active) comes first
            if (a.latency_ms < 0 && b.latency_ms >= 0) return false; // dead ports go to bottom
            if (b.latency_ms < 0 && a.latency_ms >= 0) return true;
            if (a.latency_ms < 0 && b.latency_ms < 0) return a.port < b.port; // tiebreaker
            return a.latency_ms < b.latency_ms; // lowest latency wins
        });
    }

    scanning_ = false;
    LOGI("Port scan complete. %zu ports tested.", results_.size());
}

int PortSelector::getBestPort() {
    std::lock_guard<std::mutex> lock(results_mutex_);
    for (const auto& candidate : results_) {
        if (candidate.is_active && candidate.consecutive_failures < 3) return candidate.port;
    }
    return 443; // Fallback
}

TestResult PortSelector::testSpecificPort(int port) {
    return ConnectionTester::testPortWithDomain(target_host_, port, timeout_ms_);
}

void PortSelector::setActivePort(int port) { active_port_ = port; LOGI("Active port set to: %d", port); }
int PortSelector::getActivePort() const { return active_port_; }

void PortSelector::reportPortFailure(int port) {
    std::lock_guard<std::mutex> lock(results_mutex_);
    for (auto& candidate : results_) {
        if (candidate.port == port) {
            candidate.consecutive_failures++;
            candidate.is_active = (candidate.consecutive_failures < 3);
            break;
        }
    }
}

void PortSelector::reportPortSuccess(int port) {
    std::lock_guard<std::mutex> lock(results_mutex_);
    for (auto& candidate : results_) {
        if (candidate.port == port) {
            candidate.consecutive_failures = 0;
            candidate.is_active = true;
            candidate.total_uses++;
            break;
        }
    }
}

std::vector<PortCandidate> PortSelector::getResults() const {
    std::lock_guard<std::mutex> lock(results_mutex_);
    return results_;
}