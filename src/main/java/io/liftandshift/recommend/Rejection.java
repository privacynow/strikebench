package io.liftandshift.recommend;

import java.util.List;

/** A strategy the engine looked at and refused, with the reasons — shown for education. */
public record Rejection(String strategy, String displayName, List<String> reasons) {}
