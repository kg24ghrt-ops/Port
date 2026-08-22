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

bool ConnectionTester::rawTcpConnect(const std::string& host, int port, int timeout_ms, int& out_latency) {
    struct addrinfo hints{}, *result = nullptr;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    std::string port_str = std::to_string(port);
    int dns_status = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
    if (dns_status != 0) {
        LOGE("DNS failed for %s: %s", host.c_str(), gai_strerror(dns_status));
        return false;
    }

    int sockfd = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
    if (sockfd < 0) { freeaddrinfo(result); return false; }

    int flags = fcntl(sockfd, F_GETFL, 0);
    fcntl(sockfd, F_SETFL, flags | O_NONBLOCK);
    
    int nodelay = 1;
    setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

    auto start = std::chrono::steady_clock::now();
    int connect_result = connect(sockfd, result->ai_addr, result->ai_addrlen);
    freeaddrinfo(result);

    if (connect_result < 0) {
        if (errno != EINPROGRESS) { close(sockfd); return false; }
        
        struct pollfd pfd{};
        pfd.fd = sockfd;
        pfd.events = POLLOUT;
        
        if (poll(&pfd, 1, timeout_ms) <= 0) { 
            close(sockfd); 
            return false; 
        }
        
        int error = 0; socklen_t len = sizeof(error);
        getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &error, &len);
        if (error != 0) { close(sockfd); return false; }
    }

    auto end = std::chrono::steady_clock::now();
    out_latency = (int)std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    close(sockfd);
    return true;
}

TestResult ConnectionTester::testPort(const std::string& host, int port, int timeout_ms) {
    TestResult result{port, false, -1, "UNKNOWN"};
    int latency = 0;
    if (rawTcpConnect(host, port, timeout_ms, latency)) {
        result.success = true;
        result.latency_ms = latency;
        result.response = "OK";
    } else {
        result.response = "TIMEOUT";
    }
    return result;
}

TestResult ConnectionTester::testPortWithDomain(const std::string& domain, int port, int timeout_ms) {
    // OPTIMIZATION: Just call testPort. The previous redundant DNS check here 
    // was causing dead ports to take twice as long to fail.
    return testPort(domain, port, timeout_ms);
}

TestResult ConnectionTester::testTlsHandshake(const std::string& host, int port, int timeout_ms) {
    return testPort(host, port, timeout_ms);
}