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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.StringJoiner;

import com.risquanter.metalog.Metalog;

public class ExampleUtil {

    private static final int STEPS = 100;
    private static final double EPS = 1e-12;

    static String loadResourceAsString(String resourcePath) {
        InputStream in = ObservationalMetalogFitExample.class
                .getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Resource not found: " + resourcePath);
        }
        // Use Scanner trick to read the entire stream
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    static String buildObsJson(double[] observations, double[] pValues) {
        StringJoiner obsSj = new StringJoiner(",\n  ", "[\n  ", "\n]");
        for (int i = 0; i < observations.length; i++) {
            obsSj.add(String.format(
                    "{\"quantile\": %.1f, \"p\": %.3f}",
                    observations[i], pValues[i]));
        }
        String obsJson = obsSj.toString();
        return obsJson;
    }

    /**
     * Dumps a full CDF trace from p=0.00 → p=1.00 by calling
     * m.quantile(p), where p is clamped into (EPS, 1-EPS) for each step:
     * double pForQ = Math.min(Math.max(p, EPS), 1.0 - EPS);
     * This single method works for both bounded and unbounded Metalogs:
     * it will result in exact flat caps when bounds are in force,
     * and natural extrapolation otherwise.
     */
    static String buildFitJson(Metalog m) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");

        for (int i = 0; i <= STEPS; i++) {
            double p = i / (double) STEPS;

            // clamp into (EPS,1-EPS) so quantile(0.0) or quantile(1.0) never gets called
            double pForQ = Math.min(Math.max(p, EPS), 1.0 - EPS);
            double q = m.quantile(pForQ);

            String comma = (i < STEPS ? "," : "");
            sb.append(String.format(
                    "  {\"p\": %.3f, \"quantile\": %.4f}%s%n",
                    p, q, comma));
        }

        sb.append("]");
        return sb.toString();
    }

    public static void writeToTestResource(String filename, String content) {
        File file = Paths.get("src", "test", "resources", filename).toFile();

        try {
            file.getParentFile().mkdirs();

            try (FileWriter writer = new FileWriter(file, false)) {
                writer.write(content);
            }

        } catch (IOException e) {
            System.err.println("❌ Failed to write Vega-Lite spec to file: " + file.getAbsolutePath());
            System.err.println("Reason: " + e.getMessage());
        }
    }

    static String buildRulesJson(double lower, double upper) {

        StringJoiner rulesSj = new StringJoiner(",\n  ", "[\n  ", "\n]");

        rulesSj.add(String.format(
                "{\"x\": %.3f}",
                lower));
        rulesSj.add(String.format(
                "{\"x\": %.3f}",
                upper));

        String rulesJson = rulesSj.toString();
        return rulesJson;
    }

    static String buildLabelJson(Metalog metalog, Metalog boundedMetalog) {

        StringJoiner labelSj = new StringJoiner(",\n  ", "[\n  ", "\n]");

        var pBounded = 0.95;
        var qBounded = boundedMetalog.quantile(pBounded);
        var pUnbounded = 0.05;
        var qUnbounded = metalog.quantile(pUnbounded);
        labelSj.add(String.format(
                "{\"quantile\": %.3f, \"p\": %.2f, \"label\": \"bounded\", \"color\": \"midnightblue\"}",
                qBounded, pBounded));
        labelSj.add(String.format(
                "{\"quantile\": %.3f, \"p\": %.2f, \"label\": \"unbounded\", \"color\": \"darkred\"}",
                qUnbounded, pUnbounded));

        String labelJson = labelSj.toString();
        return labelJson;
    }

}
