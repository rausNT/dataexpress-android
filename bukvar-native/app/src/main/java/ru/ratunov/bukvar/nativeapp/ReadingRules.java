package ru.ratunov.bukvar.nativeapp;
import java.text.Normalizer;
import java.util.Locale;

/** Text matching only: never an articulation or phoneme assessment. */
public final class ReadingRules {
 public enum Result { EMPTY, EXACT, SYLLABLES, DIFFERENT }
 private ReadingRules() {}
 public static String normalize(String value) {
  if (value == null) return "";
  return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("ru"))
   .replace("\u0301", "").replaceAll("[^а-яёa-z0-9\\s]", " ").trim().replaceAll("\\s+", " ");
 }
 public static Result compare(String target, String heard, String[] syllables) {
  String wanted = normalize(target), actual = normalize(heard);
  if (actual.isEmpty()) return Result.EMPTY;
  if (!wanted.isEmpty() && wanted.equals(actual)) return Result.EXACT;
  if (syllables != null && syllables.length > 1 && !wanted.contains(" ")) {
   String joined = String.join(" ", syllables);
   if (normalize(joined).equals(actual)) return Result.SYLLABLES;
  }
  return Result.DIFFERENT;
 }
}
