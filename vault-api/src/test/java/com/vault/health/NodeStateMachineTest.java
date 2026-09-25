package com.vault.health;

import com.vault.health.NodeStateMachine.State;
import com.vault.metadata.NodeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeStateMachineTest {

    private static final State HEALTHY = new State(NodeStatus.HEALTHY, 0, 0);

    @Test
    void singleMissedHeartbeatOnlySuspectsTheNode() {
        State s = NodeStateMachine.onFailure(HEALTHY, 3);
        assertEquals(NodeStatus.SUSPECTED, s.status());
    }

    @Test
    void thresholdConsecutiveFailuresMakeNodeUnhealthy() {
        State s = HEALTHY;
        for (int i = 0; i < 2; i++) {
            s = NodeStateMachine.onFailure(s, 3);
            assertEquals(NodeStatus.SUSPECTED, s.status());
        }
        s = NodeStateMachine.onFailure(s, 3);
        assertEquals(NodeStatus.UNHEALTHY, s.status());
    }

    @Test
    void successBeforeThresholdClearsSuspicion() {
        State s = NodeStateMachine.onFailure(NodeStateMachine.onFailure(HEALTHY, 3), 3);
        s = NodeStateMachine.onSuccess(s, 2);
        assertEquals(new State(NodeStatus.HEALTHY, 0, 0), s);
    }

    @Test
    void unhealthyNodeMustProveItselfBeforeBeingTrustedAgain() {
        State s = new State(NodeStatus.UNHEALTHY, 5, 0);
        s = NodeStateMachine.onSuccess(s, 2);
        assertEquals(NodeStatus.RECOVERING, s.status());
        s = NodeStateMachine.onSuccess(s, 2);
        assertEquals(NodeStatus.HEALTHY, s.status());
    }

    @Test
    void failureWhileRecoveringSendsNodeStraightBackToUnhealthy() {
        State s = NodeStateMachine.onSuccess(new State(NodeStatus.UNHEALTHY, 5, 0), 3);
        s = NodeStateMachine.onFailure(s, 3);
        assertEquals(NodeStatus.UNHEALTHY, s.status());
        assertEquals(0, s.successes());
    }

    @Test
    void recoveryThresholdOfOneTrustsImmediately() {
        assertEquals(NodeStatus.HEALTHY,
                NodeStateMachine.onSuccess(new State(NodeStatus.UNHEALTHY, 5, 0), 1).status());
    }
}
