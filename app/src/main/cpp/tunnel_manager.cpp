#include "tunnel_manager.h"
#include <chrono>
#include <android/log.h>

#define LOG_TAG "PortMawer::Tunnel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

TunnelManager& TunnelManager::getInstance() {
    static TunnelManager instance;
    return instance;
}

TunnelManager::~TunnelManager() {
    shutdown();
}

void TunnelManager::initialize(const std::string& target_host) {
    selector_.setTargetHost(target_host);
    selector_.setTimeout(4000); // 4 second timeout for testing

    stats_ = TunnelStats{};
    stats_.target_host = target_host;
    stats_.active_port = -1;

    LOGI("TunnelManager initialized for host: %s", target_host.c_str());
}

void TunnelManager::shutdown() {
    running_ = false;
    stopHealthCheck();
    setState(TunnelState::DISCONNECTED);
    LOGI("TunnelManager shut down");
}

bool TunnelManager::connect() {
    if (state_ == TunnelState::CONNECTED) return true;

    setState(TunnelState::TESTING_PORTS);

    // Find best port
    int best_port = selector_.getBestPort();

    if (best_port <= 0) {
        // No ports tested yet, test them now
        TestResult result = selector_.testSpecificPort(443);
        if (!result.success) {
            setState(TunnelState::ERROR);
            return false;
        }
        best_port = 443;
    }

    setState(TunnelState::CONNECTING);

    // Verify the port is still alive
    TestResult verify = selector_.testSpecificPort(best_port);
    if (!verify.success) {
        // Try to find another port
        best_port = selector_.getBestPort();
        verify = selector_.testSpecificPort(best_port);
        if (!verify.success) {
            setState(TunnelState::ERROR);
            return false;
        }
    }

    // Set active port
    selector_.setActivePort(best_port);
    stats_.active_port = best_port;

    setState(TunnelState::CONNECTED);
    startHealthCheck();

    LOGI("Connected via port %d", best_port);
    return true;
}

void TunnelManager::disconnect() {
    stopHealthCheck();
    stats_.active_port = -1;
    setState(TunnelState::DISCONNECTED);
    LOGI("Disconnected");
}

void TunnelManager::reconnect() {
    setState(TunnelState::RECONNECTING);
    stats_.reconnection_count++;

    // Report failure on current port
    if (stats_.active_port > 0) {
        selector_.reportPortFailure(stats_.active_port);
    }

    // Try to find a new port
    int new_port = selector_.getBestPort();
    if (new_port > 0 && new_port != stats_.active_port) {
        selector_.setActivePort(new_port);
        stats_.active_port = new_port;
        setState(TunnelState::CONNECTED);
        LOGI("Reconnected via port %d", new_port);
    } else {
        // Need to re-scan
        setState(TunnelState::ERROR);
        LOGE("No available ports for reconnection");
    }
}

TunnelState TunnelManager::getState() const {
    return state_;
}

TunnelStats TunnelManager::getStats() const {
    return stats_;
}

int TunnelManager::getActivePort() const {
    return stats_.active_port;
}

void TunnelManager::testPorts(std::function<void(int, bool)> onPortTested) {
    setState(TunnelState::TESTING_PORTS);

    selector_.startScan([onPortTested](int port, TestResult result) {
        if (onPortTested) {
            onPortTested(port, result.success);
        }
    });
}

void TunnelManager::switchToPort(int port) {
    TestResult result = selector_.testSpecificPort(port);
    if (result.success) {
        selector_.setActivePort(port);
        stats_.active_port = port;
        selector_.reportPortSuccess(port);
        setState(TunnelState::CONNECTED);
        LOGI("Switched to port %d", port);
    } else {
        LOGE("Cannot switch to port %d: connection failed", port);
    }
}

void TunnelManager::startHealthCheck() {
    if (running_.load()) return;
    running_ = true;
    health_thread_ = std::thread(&TunnelManager::healthCheckWorker, this);
}

void TunnelManager::stopHealthCheck() {
    running_ = false;
    if (health_thread_.joinable()) {
        health_thread_.join();
    }
}

void TunnelManager::healthCheckWorker() {
    LOGI("Health check started");

    while (running_.load()) {
        // Sleep 30 seconds between health checks
        for (int i = 0; i < 30 && running_.load(); i++) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
            stats_.connection_uptime_seconds++;
        }

        if (!running_.load()) break;

        // Verify the active port is still responding
        int current_port = stats_.active_port;
        if (current_port > 0) {
            TestResult result = selector_.testSpecificPort(current_port);
            if (!result.success) {
                LOGI("Health check failed on port %d, reconnecting...", current_port);
                reconnect();
            } else {
                selector_.reportPortSuccess(current_port);
            }
        }
    }

    LOGI("Health check stopped");
}

void TunnelManager::setState(TunnelState new_state) {
    state_ = new_state;
    if (state_callback_) {
        state_callback_(new_state);
    }
}

void TunnelManager::setStateCallback(StateCallback cb) {
    state_callback_ = cb;
}