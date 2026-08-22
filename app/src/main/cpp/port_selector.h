#ifndef PORTMAWER_PORT_SELECTOR_H
#define PORTMAWER_PORT_SELECTOR_H

#include "connection_tester.h"
#include <vector>
#include <string>
#include <functional>
#include <mutex>
#include <thread>
#include <atomic>

struct PortCandidate {
    int port;
    int latency_ms;
    bool is_active;
    int consecutive_failures;
    int total_uses;
};

class PortSelector {
public:
    PortSelector();
    ~PortSelector();

    // Configuration
    void setTargetHost(const std::string& host);
    void setPortRange(int start, int end);
    void addCustomPort(int port);
    void setTimeout(int ms);

    // Scanning
    void startScan(std::function<void(int, TestResult)> onResult);
    void stopScan();
    bool isScanning() const;

    // Selection
    int getBestPort();
    TestResult testSpecificPort(int port);

    // Session management
    void setActivePort(int port);
    int getActivePort() const;
    void reportPortFailure(int port);
    void reportPortSuccess(int port);

    // Get all tested ports with results
    std::vector<PortCandidate> getResults() const;

private:
    std::string target_host_;
    std::vector<int> ports_to_test_;
    std::vector<PortCandidate> results_;
    
    // *** THIS IS THE FIX ***
    // The 'mutable' keyword allows the mutex to be locked 
    // even inside a 'const' function like getResults().
    mutable std::mutex results_mutex_; 

    std::atomic<bool> scanning_{false};
    std::thread scan_thread_;

    int active_port_ = -1;
    int timeout_ms_ = 3000;

    void scanWorker(std::function<void(int, TestResult)> onResult);
};

#endif