#include "port_selector.h"
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "PortMawer::Selector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

PortSelector::PortSelector() {
    // Default ports to test
    ports_to_test_ = {
        443,      // Standard HTTPS
        8443,     // Alt HTTPS
        4443,     // Alt HTTPS 2
        2053,     // Cloudflare alt
        2083,     // Cloudflare alt 2
        2087,     // Cloudflare alt 3
        2096,     // Cloudflare alt 4
        8080,     // HTTP proxy
        8888,     // Alt HTTP
        9443,     // Alt HTTPS 3
        44321,    // Custom test port (as user mentioned)
        10443,    // Alt
        20443,    // Alt
        30443,    // Alt
    };
}

PortSelector::~PortSelector() {
    stopScan();
}

void PortSelector::setTargetHost(const std::string& host) {
    target_host_ = host;
}

void PortSelector::setPortRange(int start, int end) {
    ports_to_test_.clear();
    for (int p = start; p <= end; p++) {
        ports_to_test_.push_back(p);
    }
}

void PortSelector::addCustomPort(int port) {
    ports_to_test_.push_back(port);
}

void PortSelector::setTimeout(int ms) {
    timeout_ms_ = ms;
}

void PortSelector::startScan(std::function<void(int, TestResult)> onResult) {
    if (scanning_.load()) return;
    scanning_ = true;
    scan_thread_ = std::thread(&PortSelector::scanWorker, this, onResult);
}

void PortSelector::stopScan() {
    scanning_ = false;
    if (scan_thread_.joinable()) {
        scan_thread_.join();
    }
}

bool PortSelector::isScanning() const {
    return scanning_.load();
}

void PortSelector::scanWorker(std::function<void(int, TestResult)> onResult) {
    LOGI("Starting port scan on host: %s", target_host_.c_str());

    std::vector<PortCandidate> new_results;

    for (size_t i = 0; i < ports_to_test_.size() && scanning_.load(); i++) {
        int port = ports_to_test_[i];

        TestResult result = ConnectionTester::testPortWithDomain(
            target_host_, port, timeout_ms_
        );

        PortCandidate candidate;
        candidate.port = port;
        candidate.latency_ms = result.latency_ms;
        candidate.is_active = result.success;
        candidate.consecutive_failures = result.success ? 0 : 1;
        candidate.total_uses = 0;

        {
            std::lock_guard<std::mutex> lock(results_mutex_);
            new_results.push_back(candidate);
            results_ = new_results;
        }

        if (onResult) {
            onResult(port, result);
        }

        LOGI("Tested port %d: %s (%dms)",
             port,
             result.success ? "OK" : "FAIL",
             result.latency_ms);
    }

    // Sort by latency (successful ports first)
    std::lock_guard<std::mutex> lock(results_mutex_);
    std::sort(results_.begin(), results_.end(),
        [](const PortCandidate& a, const PortCandidate& b) {
            if (a.is_active != b.is_active) return a.is_active > b.is_active;
            if (a.latency_ms < 0) return false;
            if (b.latency_ms < 0) return true;
            return a.latency_ms < b.latency_ms;
        });

    scanning_ = false;
    LOGI("Port scan complete. %zu ports tested.", results_.size());
}

int PortSelector::getBestPort() {
    std::lock_guard<std::mutex> lock(results_mutex_);

    for (const auto& candidate : results_) {
        if (candidate.is_active && candidate.consecutive_failures < 3) {
            return candidate.port;
        }
    }

    // Fallback to 443
    return 443;
}

TestResult PortSelector::testSpecificPort(int port) {
    return ConnectionTester::testPortWithDomain(target_host_, port, timeout_ms_);
}

void PortSelector::setActivePort(int port) {
    active_port_ = port;
    LOGI("Active port set to: %d", port);
}

int PortSelector::getActivePort() const {
    return active_port_;
}

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