#ifndef PORTMAWER_CONNECTION_TESTER_H
#define PORTMAWER_CONNECTION_TESTER_H

#include <string>
#include <vector>
#include <cstdint>

struct TestResult {
    int port;
    bool success;
    int latency_ms;       // milliseconds
    std::string response; // "OK", "TIMEOUT", "REFUSED", etc.
};

class ConnectionTester {
public:
    // Test a single port with TCP connect + optional HTTP probe
    static TestResult testPort(
        const std::string& host,
        int port,
        int timeout_ms = 3000
    );

    // Test with a domain-resolved approach (try resolving then connecting)
    static TestResult testPortWithDomain(
        const std::string& domain,
        int port,
        int timeout_ms = 3000
    );

    // Send a minimal TLS ClientHello probe to check if HTTPS responds
    static TestResult testTlsHandshake(
        const std::string& host,
        int port,
        int timeout_ms = 5000
    );

private:
    static bool rawTcpConnect(
        const std::string& host,
        int port,
        int timeout_ms,
        int& out_latency
    );
};

#endif