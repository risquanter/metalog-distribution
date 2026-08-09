/*
 * Copyright (C) 2025 Daniel Agota <danago@risquanter.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.risquanter.examples;

import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.random.Well19937c;

import com.risquanter.metalog.Metalog;
import com.risquanter.metalog.QPFitter;

import org.apache.commons.math3.random.RandomGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


/**
 * Example that builds a risk hierarchy of three independent risks (A, B, C),
 * each modeled as a two-part distribution:
 * <ul>
 *   <li>A Bernoulli loss event with given probability of loss</li>
 *   <li>A continuous tail loss modeled by a fitted Metalog distribution</li>
 * </ul>
 * <p>
 * For each risk (A, B, C), there is a chance (probability pLoss) that an event occurs.
 * If the event occurs, the loss amount is sampled from a lognormal distribution
 * (approximated by a fitted Metalog). If the event does not occur, the loss is zero.
 * The Monte Carlo loop implements this logic: for each simulation, it flips a coin
 * for each risk, and if the coin indicates an event, it samples a tail loss;
 * otherwise, it adds zero.
 * <p>
 * The Metalog tail at each leaf is fitted to quantiles sampled from a
 * LogNormal distribution that covers the user-specified [lossLB, lossUB]
 * at the central 80% interval.
 * <p>
 * The example then runs a Monte Carlo simulation to produce loss exceedance
 * curves (LEC) for the combined risks AB and ABC, printing the full LEC
 * on a p-grid from p=0.00 to p=1.00 as JSON.
 */
public class RiskHierarchyMetalogPGrid {
    // how many p-steps to print on [0,1]
    private static final int GRID_STEPS = 100;

    public static void main(String[] args) {
        // --- 1) User inputs: [lossLB, lossUB, pLoss] for each leaf
        double[][] inputs = {
            {100, 200, 0.10},  // A
            { 50, 150, 0.20},  // B
            { 80, 300, 0.05}   // C
        };

        // one fixed seed for everything
        long seed = 42L;
        RandomGenerator hdrRng = new Well19937c(seed);
        Random      uniRng = new Random(seed);

        // --- 2) Fit *only* the continuous tail metalog at each leaf
        Metalog[] leafTailMetalog = new Metalog[inputs.length];
        for (int r = 0; r < inputs.length; r++) {
            double lb    = inputs[r][0];
            double ub    = inputs[r][1];
            //double pLoss = inputs[r][2];
            //double pNo   = 1.0 - pLoss;

            // pick 9 points in the (0,1) → tail
            // we only anchor the nonzero portion at normalized probabilities
            double[] tailP = { 0.001,0.02,0.10,0.25,0.50,0.75,0.90,0.98,0.999 };
            List<Double> pAnch = new ArrayList<>();
            List<Double> xAnch = new ArrayList<>();

            // build a LogNormal that covers [lb,ub] at central 80%
            double meanLog = (Math.log(lb) + Math.log(ub))/2.0;
            double varLog  = Math.pow((Math.log(ub)-Math.log(lb))/3.29, 2);
            LogNormalDistribution lnDist =
                new LogNormalDistribution(hdrRng, meanLog, Math.sqrt(varLog));

            for (double tp : tailP) {
                pAnch.add(tp);
                xAnch.add( lnDist.inverseCumulativeProbability(tp) );
            }

            double[] pArr = pAnch.stream().mapToDouble(Double::doubleValue).toArray();
            double[] xArr = xAnch.stream().mapToDouble(Double::doubleValue).toArray();

            // fit a pure‐tail metalog
            leafTailMetalog[r] = QPFitter
                .with(pArr, xArr, pArr.length)
                .fit();
        }

        // --- 3) Monte Carlo: build AB and ABC by coin-flip + tail‐sample
        int sims = 10_000;
        double[] ab  = new double[sims];
        double[] abc = new double[sims];

        for (int i = 0; i < sims; i++) {
            // A+B
            double sumAB = 0.0;
            for (int r = 0; r < 2; r++) {
                double pLoss = inputs[r][2];
                double flip  = uniRng.nextDouble();
                if (flip < pLoss) {
                    // sample tail
                    double uTail = uniRng.nextDouble();
                    sumAB += leafTailMetalog[r].quantile(uTail);
                }
                // else zero
            }
            ab[i] = sumAB;

            // then add C
            double sumABC = sumAB;
            {
                double pLoss = inputs[2][2];
                double flip  = uniRng.nextDouble();
                if (flip < pLoss) {
                    double uTail = uniRng.nextDouble();
                    sumABC += leafTailMetalog[2].quantile(uTail);
                }
            }
            abc[i] = sumABC;
        }

        // sort once
        java.util.Arrays.sort(ab);
        java.util.Arrays.sort(abc);

        // --- 4) Print full loss-exceedance curve on p-grid
        System.out.println("[");
        for (int i = 1; i <= GRID_STEPS; i++) {
            double cdf_p = i/(double)GRID_STEPS;  // 0.01 to 1.00
            double exceedance_p = 1.0 - cdf_p;   // 0.99 to 0.00

            int idx = (int)Math.floor(cdf_p * sims);
            if (idx >= sims) idx = sims-1;

            double qAB  = ab[idx];
            double qABC = abc[idx];
            String comma = (i < GRID_STEPS ? "," : "");

            System.out.printf(
                "  {\"exceedance_p\": %.3f, \"AB\": %.4f, \"ABC\": %.4f}%s%n",
                exceedance_p, qAB, qABC, comma
            );
        }
        System.out.println("]");
    }
}
