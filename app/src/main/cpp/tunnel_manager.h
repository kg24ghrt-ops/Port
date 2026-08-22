#ifndef PORTMAWER_TUNNEL_MANAGER_H
#define PORTMAWER_TUNNEL_MANAGER_H

#include "port_selector.h"
#include <string>
#include <atomic>
#include <functional>

enum class TunnelState {
    DISCONNECTED,
    TESTING_PORTS,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
};

struct TunnelStats {
    int active_port;
    int total_bytes_sent;
    int total_bytes_received;
    int connection_uptime_seconds;
    int reconnection_count;
    std::string target_host;
};

class TunnelManager {
public:
    static TunnelManager& getInstance();

    // Lifecycle
    void initialize(const std::string& target_host);
    void shutdown();

    // Connection
    bool connect();
    void disconnect();
    void reconnect();

    // State
    TunnelState getState() const;
    TunnelStats getStats() const;
    int getActivePort() const;

    // Port management
    void testPorts(std::function<void(int, bool)> onPortTested);
    void switchToPort(int port);

    // Health monitoring
    void startHealthCheck();
    void stopHealthCheck();

    // Callbacks
    using StateCallback = std::function<void(TunnelState)>;
    void setStateCallback(StateCallback cb);

private:
    TunnelManager() = default;
    ~TunnelManager();

    TunnelManager(const TunnelManager&) = delete;
    TunnelManager& operator=(const TunnelManager&) = delete;

    PortSelector selector_;
    TunnelState state_ = TunnelState::DISCONNECTED;
    TunnelStats stats_{};
    StateCallback state_callback_;

    std::atomic<bool> running_{false};
    std::thread health_thread_;

    void healthCheckWorker();
    void setState(TunnelState new_state);
};

#endif