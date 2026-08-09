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

import static com.risquanter.examples.ExampleUtil.loadResourceAsString;
import static com.risquanter.examples.ExampleUtil.buildObsJson;
import static com.risquanter.examples.ExampleUtil.buildFitJson;
import static com.risquanter.examples.ExampleUtil.writeToTestResource;

import com.risquanter.metalog.Metalog;
import com.risquanter.metalog.QPFitter;

import static com.risquanter.examples.ExampleUtil.buildRulesJson;
import static com.risquanter.examples.ExampleUtil.buildLabelJson;


/**
 * Expert‐Opinion CDF Example using a fully‐bounded Metalog (support [L,U]).
 *
 * Scenario:
 *   An expert provides three quantile estimates for
 *   completion time (days):
 *     •  5th percentile  (p=0.05):  3.0 days  ← lower bound L
 *     • 50th percentile  (p=0.50):  6.0 days
 *     • 95th percentile  (p=0.95): 10.0 days  ← upper bound U
 *
 * Steps:
 *   1) Define the expert’s (pᵢ, Qᵢ) triplet.
 *   2) Fit a 3-term fully-bounded metalog with support [LS=3.0, US=10.0].
 *   3) Generate evenly-spaced p values in [0.05, 0.95] and emit JSON
 *      of { "quantile": Q(p), "p": p } for Vega-Lite CDF plotting.
 *   4) Fill a Vega-Lite spec template with the generated data and write it to a test resource file.
 *   5) Print the quantile values at the endpoints to verify the bounded fit.
 *
 * The output can be visualized by pasting it into the online Vega-Lite editor:
 * https://vega.github.io/editor/#/examples/vega-lite/bar_chart
 */
public class ExpertOpinionDemo2 {
    public static void main(String[] args) {

        String outputFilename = "vega-lite-expert-opinion-2.json";

        // 1) Expert quantiles
        double[] pVals      = { 0.05, 0.50, 0.95 };
        double[] xVals      = {  3.0,  6.0, 10.0 };
        // choose true support bounds so  LB < min(xVals) < max(xVals) < UB
        double   lowerBound =  0.0;    // e.g. no negative days
        double   upperBound = 11.0;    // safely above the pessimistic estimate
        int      terms      = pVals.length;

        // 2) Fit fully-bounded metalog
        Metalog metalog = QPFitter.with(pVals, xVals, terms).fit();

        Metalog boundedMetalog = QPFitter.with(pVals, xVals, terms).lower(lowerBound).upper(upperBound).fit();

        // 4) Build Vega-Lite JSON representation of the original data and for the
        // fitted CDF for visual comparison
        // with the original observations highlighted.
        String obsJson = buildObsJson(xVals, pVals);
        String fitJson = buildFitJson(metalog);
        String exactFitJson = buildFitJson(boundedMetalog);
        String labelJson = buildLabelJson(metalog, boundedMetalog);
        String rulesJson = buildRulesJson(lowerBound, upperBound);

        // 5) Load the stub template from test‐resources
        String template = loadResourceAsString("/vega-lite-expert-opinion-2-stub-twolines.json");

        // 6) Replace the two placeholders
        String spec = template
                .replace("\"PLACEHOLDER_OBS\"", obsJson)
                .replace("\"PLACEHOLDER_FIT_1\"", fitJson)
                .replace("\"PLACEHOLDER_FIT_2\"", exactFitJson)
                .replace("\"PLACEHOLDER_LABELS\"", labelJson)
                .replace("\"PLACEHOLDER_RULES\"", rulesJson)
                .replace("RULES_COLOR", "midnightblue");

        // 7) Print out the filled spec
        writeToTestResource(outputFilename, spec);

        System.out.println("Results written to: " + outputFilename);
    }
}
