package com.vault.health;

import com.vault.metadata.NodeStatus;

/**
 * Pure transition rules for node health, kept free of I/O so they can be unit tested exhaustively.
 * <pre>
 *   HEALTHY --miss--> SUSPECTED --(failureThreshold consecutive misses)--> UNHEALTHY
 *   UNHEALTHY --ok--> RECOVERING --(recoveryThreshold consecutive oks)--> HEALTHY
 *   SUSPECTED --ok--> HEALTHY          RECOVERING --miss--> UNHEALTHY
 * </pre>
 * A single missed heartbeat never triggers repair: only UNHEALTHY does.
 */
public final class NodeStateMachine {

    private NodeStateMachine() {
    }

    public record State(NodeStatus status, int failures, int successes) {
    }

    public static State onSuccess(State cur, int recoveryThreshold) {
        return switch (cur.status()) {
            case HEALTHY, SUSPECTED -> new State(NodeStatus.HEALTHY, 0, 0);
            case UNHEALTHY -> recoveryThreshold <= 1
                    ? new State(NodeStatus.HEALTHY, 0, 0)
                    : new State(NodeStatus.RECOVERING, 0, 1);
            case RECOVERING -> {
                int successes = cur.successes() + 1;
                yield successes >= recoveryThreshold
                        ? new State(NodeStatus.HEALTHY, 0, 0)
                        : new State(NodeStatus.RECOVERING, 0, successes);
            }
        };
    }

    public static State onFailure(State cur, int failureThreshold) {
        int failures = cur.failures() + 1;
        return switch (cur.status()) {
            case UNHEALTHY, RECOVERING -> new State(NodeStatus.UNHEALTHY, failures, 0);
            case HEALTHY, SUSPECTED -> failures >= failureThreshold
                    ? new State(NodeStatus.UNHEALTHY, failures, 0)
                    : new State(NodeStatus.SUSPECTED, failures, 0);
        };
    }
}
