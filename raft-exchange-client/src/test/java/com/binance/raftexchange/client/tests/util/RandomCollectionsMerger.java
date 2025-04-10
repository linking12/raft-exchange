package com.binance.raftexchange.client.tests.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Spliterator;
import java.util.stream.Collectors;

@Slf4j
public class RandomCollectionsMerger {

    public static <T> ArrayList<T> mergeCollections(final Collection<? extends Collection<T>> chunks, final long seed) {

        final JDKRandomGenerator jdkRandomGenerator = new JDKRandomGenerator(Long.hashCode(seed));

        final ArrayList<T> mergedResult = new ArrayList<>();

        // create initial weight pairs
        List<Pair<Spliterator<T>, Double>> weightPairs = chunks.stream()
                .map(chunk -> Pair.create(chunk.spliterator(), (double) chunk.size()))
                .collect(Collectors.toList());

        while (!weightPairs.isEmpty()) {

            final EnumeratedDistribution<Spliterator<T>> ed = new EnumeratedDistribution<>(jdkRandomGenerator, weightPairs);

            // take random elements until face too many misses
            int missCounter = 0;
            while (missCounter++ < 3) {
                final Spliterator<T> sample = ed.sample();
                if (sample.tryAdvance(mergedResult::add)) {
                    missCounter = 0;
                }
            }

            // as empty queues leading to misses - rebuild wight pairs without them
            weightPairs = weightPairs.stream()
                    .filter(p -> p.getFirst().estimateSize() > 0)
                    .map(p -> Pair.create(p.getFirst(), (double) p.getFirst().estimateSize()))
                    .collect(Collectors.toList());

//            log.debug("rebuild size {}", weightPairs.size());
        }

        return mergedResult;
    }
}
