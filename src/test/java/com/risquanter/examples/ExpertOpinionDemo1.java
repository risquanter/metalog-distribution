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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;

import com.risquanter.metalog.Metalog;
import com.risquanter.metalog.QPFitter;

import static com.risquanter.examples.ExampleUtil.loadResourceAsString;
import static com.risquanter.examples.ExampleUtil.buildObsJson;
import static com.risquanter.examples.ExampleUtil.buildRulesJson;
import static com.risquanter.examples.ExampleUtil.buildLabelJson;
import static com.risquanter.examples.ExampleUtil.buildFitJson;;

/**
 * Expert‐Opinion Metalog Example: Modeling Risk Estimates
 *
 * Scenario:
 *   An expert provides three quantile estimates for the possible loss amount (in $ thousands)
 *   in a risk assessment:
 *     •  10th percentile  (p=0.10):  $17k  ← lower bound L
 *     •  50th percentile  (p=0.50):  $24k
 *     •  90th percentile  (p=0.90):  $35k  ← upper bound U
 *
 * Steps:
 *   1) Define the expert’s (pᵢ, Qᵢ) triplet.
 *   2) Fit both an unbounded and a fully-bounded Metalog distribution with support [16, 40].
 *   3) Generate JSON traces for the fitted distributions and expert points for Vega-Lite visualization.
 *   4) Fill a Vega-Lite spec template with the generated data and write it to a test resource file.
 *   5) Print the quantile values at the endpoints to verify the bounded fit.
 *
 * The output can be visualized by pasting it into the online Vega-Lite editor:
 * https://vega.github.io/editor/#/examples/vega-lite/bar_chart
 */
public class ExpertOpinionDemo1 {
    private static final double EPS   = 1e-12;  // must match ε-anchors in the fitter


    public static void main(String[] args) {
        String outputFilename = "vega-lite-expert-opinion-1.json";

        // 1) Expert’s quantiles
        double[] pVals = {0.10, 0.50, 0.90};
        double[] xVals = {17.0, 24.0, 35.0};
        int terms      = pVals.length;

        Double lowerBound = 16.0;
        Double upperBound = 40.0;

        // 2) Fit two Metalogs: unbounded and bounded
        Metalog metalogUnbounded = QPFitter
            .with(pVals, xVals, terms)
            .fit();

        Metalog metalogBounded = QPFitter
            .with(pVals, xVals, terms)
            .lower(lowerBound)
            .upper(upperBound)
            .fit();

        // 3) Build Vega-Lite JSON snippets
        String obsJson      = buildObsJson(xVals, pVals);
        String fitJsonUnb   = buildFitJson(metalogUnbounded);
        String fitJsonBd    = buildFitJson(metalogBounded);
        String labelJson    = buildLabelJson(metalogUnbounded, metalogBounded);
        String rulesJson    = buildRulesJson(lowerBound, upperBound);

        // 4) Load template and replace placeholders
        String template = loadResourceAsString(
            "/vega-lite-expert-opinion-1-stub-twolines.json"
        );
        String spec = template
            .replace("\"PLACEHOLDER_OBS\"",    obsJson)
            .replace("\"PLACEHOLDER_FIT_1\"",  fitJsonUnb)
            .replace("\"PLACEHOLDER_FIT_2\"",  fitJsonBd)
            .replace("\"PLACEHOLDER_LABELS\"", labelJson)
            .replace("\"PLACEHOLDER_RULES\"",  rulesJson)
            .replace("RULES_COLOR",            "midnightblue");

        // 5) Write the filled spec out
        writeToTestResource(outputFilename, spec);
        System.out.println("Results written to: " + outputFilename);

        // 6) Verify that the bounded fit is pinned at the ends
        System.out.println("Bounded Metalog Quantiles at the Anchors:");
        System.out.printf("Q(eps)    = %.12f%n", metalogBounded.quantile(EPS));
        System.out.printf("Q(1-eps)  = %.12f%n", metalogBounded.quantile(1 - EPS));
    }



    /**
     * Writes `content` to src/test/resources/`filename`, creating
     * directories as needed.
     */
    public static void writeToTestResource(String filename, String content) {
        File file = Paths.get("src", "test", "resources", filename).toFile();
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(content);
        } catch (IOException e) {
            System.err.println("❌ Failed to write Vega-Lite spec to file: "
                               + file.getAbsolutePath());
            e.printStackTrace();
        }
    }
}
