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

import java.util.Arrays;

import com.risquanter.metalog.Metalog;
import com.risquanter.metalog.QPFitter;

import static com.risquanter.examples.ExampleUtil.loadResourceAsString;
import static com.risquanter.examples.ExampleUtil.buildObsJson;
import static com.risquanter.examples.ExampleUtil.buildFitJson;
import static com.risquanter.examples.ExampleUtil.writeToTestResource;


/**
 * Observational Metalog Fit Example
 *
 * Scenario:
 *   Given a set of raw observations, fit a Metalog distribution
 *   to approximate the empirical CDF.
 *
 * Steps:
 *   1) Define the raw observations.
 *   2) Compute plotting positions pᵢ using Hazen's formula: pᵢ = (i + 0.5) / n.
 *   3) Fit a Metalog distribution to the (pᵢ, xᵢ) pairs.
 *   4) Generate JSON traces for the fitted distribution and original observations for Vega-Lite visualization.
 *   5) Fill a Vega-Lite spec template with the generated data and write it to a test resource file.
 *
 * The output can be visualized by pasting it into the online Vega-Lite editor:
 * https://vega.github.io/editor/#/examples/vega-lite/bar_chart
 */
public class ObservationalMetalogFitExample {
    public static void main(String[] args) {

        String outputFilename = "vega-lite-observational.json";

        // 1) Raw observations (n = 9)
        //TODO: values marked with (!) for further testing, as they cause numerical instability
        //double[] observations = { 78, 65, 82, 90, 120 /* ! */, 160, 143 /* ! */, /* 127 ,*/ 110, 85 };
        //double[] observations = { 78, 65, 82, 85,  160, 143 ,  127 , 110, 85 };
        double[] observations = { 78, 65, 82, 90, 160, 143, 127, 110, 85 };
        int n = observations.length;
        Arrays.sort(observations);

        // 2) Compute plotting positions p_i = (i + 0.5) / n
        // Hazen plotting positions estimate the empirical CDF by assigning each sorted
        // observation a probability p in (0,1), using the formula p = (i + 0.5) / n.
        // We use them to generate valid p-values when they are not directly available
        // from expert estimates or simulation-based approximations.
        double[] pValues = new double[n];
        for (int i = 0; i < n; i++) {
            pValues[i] = (i + 0.5) / n; // Hazen plotting positions
        }

        // 3) Fit a metalog to 9 data points with 5 terms and 9 terms (exact fit)
        // (more terms = more flexible, but also more complexity / chance for overfitting)
        int terms = 4;

        Metalog metalog = QPFitter.with(pValues, observations, terms).lower(0.0).upper(170.0).fit();

        Metalog exactMetalog = QPFitter.with(pValues, observations, 9).lower(0.0).upper(170.0).fit();

        // 4) Build Vega-Lite JSON representation of the original data and for the
        // fitted CDF for visual comparison
        // with the original observations highlighted.
        String obsJson = buildObsJson(observations, pValues);
        String fitJson = buildFitJson(metalog);
        String exactFitJson = buildFitJson(exactMetalog);

        // 5) Load the stub template from test‐resources
        String template = loadResourceAsString("/vega-lite-observational-stub-twolines.json");

        // 6) Replace the two placeholders
        String spec = template
                .replace("\"PLACEHOLDER_OBS\"", obsJson)
                .replace("\"PLACEHOLDER_FIT_1\"", fitJson)
                .replace("\"PLACEHOLDER_FIT_2\"", exactFitJson);

        // 7) Print out the filled spec
        writeToTestResource(outputFilename, spec);

        System.out.println("Results written to: " + outputFilename);

    }


}
