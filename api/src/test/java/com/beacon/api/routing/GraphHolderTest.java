package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.graphhopper.GraphHopper;
import org.junit.jupiter.api.Test;

class GraphHolderTest {

    @Test
    void swapPublishesTheReplacementAndClosesTheOldGraph() {
        GraphHopper original = mock(GraphHopper.class);
        GraphHopper replacement = mock(GraphHopper.class);
        GraphHolder holder = new GraphHolder(original);

        holder.swap(replacement);

        assertThat(holder.get()).isSameAs(replacement);
        verify(original).close();
        verify(replacement, never()).close();
    }

    @Test
    void workSeesOneStableGraphEvenIfItIsSwappedMeanwhile() {
        GraphHopper original = mock(GraphHopper.class);
        GraphHolder holder = new GraphHolder(original);

        GraphHopper seen = holder.withGraph(graph -> graph);

        assertThat(seen).isSameAs(original);
        assertThat(holder.inFlight()).as("the counter is released afterwards").isZero();
    }

    @Test
    void inFlightWorkIsCountedWhileItRuns() {
        GraphHolder holder = new GraphHolder(mock(GraphHopper.class));

        int duringCall = holder.withGraph(graph -> holder.inFlight());

        assertThat(duringCall).isEqualTo(1);
    }

    @Test
    void theCounterIsReleasedEvenWhenWorkThrows() {
        GraphHolder holder = new GraphHolder(mock(GraphHopper.class));

        assertThatThrownBy(() -> holder.withGraph(graph -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(holder.inFlight()).isZero();
    }

    @Test
    void swappingInTheSameGraphDoesNotCloseIt() {
        GraphHopper only = mock(GraphHopper.class);
        GraphHolder holder = new GraphHolder(only);

        holder.swap(only);

        verify(only, never()).close();
        assertThat(holder.get()).isSameAs(only);
    }

    @Test
    void aNullReplacementIsRejectedSoTheLiveGraphSurvives() {
        GraphHopper original = mock(GraphHopper.class);
        GraphHolder holder = new GraphHolder(original);

        assertThatThrownBy(() -> holder.swap(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.get()).isSameAs(original);
        verify(original, never()).close();
    }

    @Test
    void loadedAtAdvancesOnSwap() throws InterruptedException {
        GraphHolder holder = new GraphHolder(mock(GraphHopper.class));
        var before = holder.loadedAt();
        Thread.sleep(5);

        holder.swap(mock(GraphHopper.class));

        assertThat(holder.loadedAt()).isAfterOrEqualTo(before);
    }
}
