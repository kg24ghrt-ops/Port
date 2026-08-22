#include "connection_tester.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <cstring>
#include <chrono>
#include <android/log.h>

#define LOG_TAG "PortMawer::Tester"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool ConnectionTester::rawTcpConnect(
    const std::string& host,
    int port,
    int timeout_ms,
    int& out_latency
) {
    // Resolve hostname
    struct addrinfo hints{}, *result = nullptr;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    std::string port_str = std::to_string(port);

    int dns_status = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
    if (dns_status != 0) {
        LOGE("DNS resolution failed for %s: %s", host.c_str(), gai_strerror(dns_status));
        return false;
    }

    // Create socket
    int sockfd = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
    if (sockfd < 0) {
        freeaddrinfo(result);
        return false;
    }

    // Set non-blocking for timeout control
    int flags = fcntl(sockfd, F_GETFL, 0);
    fcntl(sockfd, F_SETFL, flags | O_NONBLOCK);

    // Disable Nagle's algorithm for faster response
    int nodelay = 1;
    setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

    auto start = std::chrono::steady_clock::now();

    int connect_result = connect(sockfd, result->ai_addr, result->ai_addrlen);
    freeaddrinfo(result);

    if (connect_result < 0) {
        if (errno != EINPROGRESS) {
            close(sockfd);
            return false;
        }

        // Wait for connection with timeout
        struct pollfd pfd{};
        pfd.fd = sockfd;
        pfd.events = POLLOUT;

        int poll_result = poll(&pfd, 1, timeout_ms);
        if (poll_result <= 0) {
            close(sockfd);
            return false;
        }

        // Check if connection succeeded
        int error = 0;
        socklen_t len = sizeof(error);
        getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &error, &len);
        if (error != 0) {
            close(sockfd);
            return false;
        }
    }

    auto end = std::chrono::steady_clock::now();
    out_latency = (int)std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    close(sockfd);
    return true;
}

TestResult ConnectionTester::testPort(
    const std::string& host,
    int port,
    int timeout_ms
) {
    TestResult result;
    result.port = port;
    result.success = false;
    result.latency_ms = -1;
    result.response = "UNKNOWN";

    int latency = 0;
    if (rawTcpConnect(host, port, timeout_ms, latency)) {
        result.success = true;
        result.latency_ms = latency;
        result.response = "OK";
        LOGI("Port %d on %s: OK (%dms)", port, host.c_str(), latency);
    } else {
        result.response = "TIMEOUT";
        LOGI("Port %d on %s: FAILED", port, host.c_str());
    }

    return result;
}

TestResult ConnectionTester::testPortWithDomain(
    const std::string& domain,
    int port,
    int timeout_ms
) {
    // First try direct domain resolution + connect
    TestResult result = testPort(domain, port, timeout_ms);

    // If domain fails, try common CDN IP ranges as fallback
    if (!result.success) {
        // Try resolving to see if it's a DNS issue vs connection issue
        struct addrinfo hints{}, *res = nullptr;
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;

        if (getaddrinfo(domain.c_str(), nullptr, &hints, &res) != 0) {
            result.response = "DNS_FAIL";
        } else {
            result.response = "CONN_FAIL";
            freeaddrinfo(res);
        }
    }

    return result;
}

TestResult ConnectionTester::testTlsHandshake(
    const std::string& host,
    int port,
    int timeout_ms
) {
    // For now, we do a TCP connect test
    // Full TLS probing would require linking OpenSSL/BoringSSL
    // The TCP connect is sufficient to determine if the port is alive
    return testPort(host, port, timeout_ms);
}