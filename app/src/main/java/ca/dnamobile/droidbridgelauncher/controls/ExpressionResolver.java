/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.controls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class ExpressionResolver {

    private final String text;
    private int position;

    private ExpressionResolver(@NonNull String text) {
        this.text = text;
    }

    static float resolve(@Nullable String input, float fallback, int screenWidth, int screenHeight, float controlWidth, float controlHeight) {
        return resolve(input, fallback, screenWidth, screenHeight, controlWidth, controlHeight, 1f, 100f, 1f, 1f);
    }

    static float resolve(
            @Nullable String input,
            float fallback,
            int screenWidth,
            int screenHeight,
            float controlWidth,
            float controlHeight,
            float density,
            float preferredScale
    ) {
        return resolve(input, fallback, screenWidth, screenHeight, controlWidth, controlHeight, density, preferredScale, density, density);
    }

    static float resolve(
            @Nullable String input,
            float fallback,
            int screenWidth,
            int screenHeight,
            float controlWidth,
            float controlHeight,
            float density,
            float preferredScale,
            float pixelScale
    ) {
        return resolve(input, fallback, screenWidth, screenHeight, controlWidth, controlHeight,
                density, preferredScale, pixelScale, density);
    }

    static float resolve(
            @Nullable String input,
            float fallback,
            int screenWidth,
            int screenHeight,
            float controlWidth,
            float controlHeight,
            float density,
            float preferredScale,
            float pixelScale,
            float dpScale
    ) {
        if (input == null || input.trim().isEmpty()) return fallback;
        float safeDensity = density > 0f ? density : 1f;
        float safePixelScale = pixelScale > 0f ? pixelScale : safeDensity;
        float safeDpScale = dpScale > 0f ? dpScale : safeDensity;
        float safePreferredScale = preferredScale > 0f ? preferredScale : 100f;
        float margin = 2f * safeDensity;

        String prepared = input.trim()
                .replace("${screen_width}", String.valueOf(screenWidth))
                .replace("${screen_height}", String.valueOf(screenHeight))
                .replace("${top}", "0")
                .replace("${left}", "0")
                .replace("${width}", String.valueOf(controlWidth))
                .replace("${height}", String.valueOf(controlHeight))
                .replace("${preferred_scale}", String.valueOf(safePreferredScale))
                .replace("${scale}", String.valueOf(safePreferredScale))
                .replace("${density}", String.valueOf(safeDensity))
                .replace("${pixel_scale}", String.valueOf(safePixelScale))
                .replace("${margin}", String.valueOf(margin))
                .replace("${right}", String.valueOf(Math.max(0f, screenWidth - controlWidth)))
                .replace("${bottom}", String.valueOf(Math.max(0f, screenHeight - controlHeight)));

        prepared = replaceUnitFunctions(prepared, safePixelScale, safeDpScale);

        try {
            ExpressionResolver parser = new ExpressionResolver(prepared);
            double value = parser.parseExpression();
            parser.skipSpaces();
            if (parser.position != prepared.length()) throw new IllegalArgumentException("Unexpected token at " + parser.position);
            if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
            return (float) value;
        } catch (Throwable ignored) {
            try { return Float.parseFloat(prepared); }
            catch (Throwable ignoredAgain) { return fallback; }
        }
    }

    @NonNull
    private static String replaceUnitFunctions(
            @NonNull String input,
            float pixelScale,
            float dpScale
    ) {
        String result = input;
        for (int pass = 0; pass < 64; pass++) {
            UnitFunctionCall call = findLastUnitFunction(result);
            if (call == null) return result;

            int close = findMatchingParenthesis(result, call.openParenthesis);
            if (close < 0) return result;

            String argument = result.substring(call.openParenthesis + 1, close);
            double argumentValue;
            try {
                ExpressionResolver parser = new ExpressionResolver(argument);
                argumentValue = parser.parseExpression();
                parser.skipSpaces();
                if (parser.position != argument.length()) return result;
            } catch (Throwable ignored) {
                return result;
            }

            double scale = call.pixelFunction ? pixelScale : dpScale;
            String replacement = Double.toString(argumentValue * scale);
            result = result.substring(0, call.start) + replacement + result.substring(close + 1);
        }
        return result;
    }

    @Nullable
    private static UnitFunctionCall findLastUnitFunction(@NonNull String input) {
        UnitFunctionCall last = null;
        for (int i = 0; i < input.length(); i++) {
            boolean pixelFunction;
            int nameLength;
            if (matchesFunctionName(input, i, "px")) {
                pixelFunction = true;
                nameLength = 2;
            } else if (matchesFunctionName(input, i, "dp")) {
                pixelFunction = false;
                nameLength = 2;
            } else {
                continue;
            }

            int open = i + nameLength;
            while (open < input.length() && Character.isWhitespace(input.charAt(open))) open++;
            if (open < input.length() && input.charAt(open) == '(') {
                last = new UnitFunctionCall(i, open, pixelFunction);
            }
        }
        return last;
    }

    private static boolean matchesFunctionName(@NonNull String input, int start, @NonNull String name) {
        if (start + name.length() > input.length()) return false;
        if (!input.regionMatches(true, start, name, 0, name.length())) return false;
        if (start > 0) {
            char before = input.charAt(start - 1);
            if (Character.isLetterOrDigit(before) || before == '_') return false;
        }
        int after = start + name.length();
        return after >= input.length()
                || !(Character.isLetterOrDigit(input.charAt(after)) || input.charAt(after) == '_');
    }

    private static int findMatchingParenthesis(@NonNull String input, int open) {
        int depth = 0;
        for (int i = open; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static final class UnitFunctionCall {
        final int start;
        final int openParenthesis;
        final boolean pixelFunction;

        UnitFunctionCall(int start, int openParenthesis, boolean pixelFunction) {
            this.start = start;
            this.openParenthesis = openParenthesis;
            this.pixelFunction = pixelFunction;
        }
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipSpaces();
            if (match('+')) value += parseTerm();
            else if (match('-')) value -= parseTerm();
            else return value;
        }
    }

    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipSpaces();
            if (match('*')) value *= parseFactor();
            else if (match('/')) value /= parseFactor();
            else return value;
        }
    }

    private double parseFactor() {
        skipSpaces();
        if (match('+')) return parseFactor();
        if (match('-')) return -parseFactor();
        if (match('(')) {
            double value = parseExpression();
            if (!match(')')) throw new IllegalArgumentException("Missing )");
            return value;
        }
        int start = position;
        boolean seenDot = false;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c >= '0' && c <= '9') position++;
            else if (c == '.' && !seenDot) { seenDot = true; position++; }
            else break;
        }
        if (start == position) throw new IllegalArgumentException("Expected number at " + position + " in " + text);
        return Double.parseDouble(text.substring(start, position));
    }

    private boolean match(char expected) {
        skipSpaces();
        if (position < text.length() && text.charAt(position) == expected) { position++; return true; }
        return false;
    }

    private void skipSpaces() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
    }
}
