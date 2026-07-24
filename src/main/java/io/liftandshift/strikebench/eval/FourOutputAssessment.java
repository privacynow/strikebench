package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.position.PositionDomain;

import java.util.List;

/** Mechanics, economics, objective fit, and portfolio impact remain independent outputs. */
public record FourOutputAssessment(
        MechanicalAssessment mechanics,
        EconomicAssessment economics,
        ObjectiveCoherence coherence,
        PortfolioImpacts portfolioImpacts
) {
    public record MechanicalAssessment(boolean eligible, List<String> reasons) {
        public MechanicalAssessment { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    }

    public record ObjectiveCoherence(Coherence verdict, String directionAssessment,
                                     String durationAssessment, List<String> reasons) {
        public ObjectiveCoherence {
            if (verdict == null) throw new IllegalArgumentException("coherence verdict is required");
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public enum Coherence { UNDECLARED, COHERENT, MIXED, INCOHERENT, UNAVAILABLE }

    public record PortfolioImpact(PositionDomain.ExecutionLane lane,
                                  long grossExposureBeforeCents, long grossExposureAfterCents,
                                  long netExposureBeforeCents, long netExposureAfterCents,
                                  Double symbolConcentrationBeforePct,
                                  Double symbolConcentrationAfterPct,
                                  List<String> concentrationChanges,
                                  String basis) {
        public PortfolioImpact {
            if (lane == null || lane == PositionDomain.ExecutionLane.NONE) {
                throw new IllegalArgumentException("portfolio impact requires a concrete lane");
            }
            concentrationChanges = concentrationChanges == null ? List.of() : List.copyOf(concentrationChanges);
            if (basis == null || basis.isBlank()) {
                throw new IllegalArgumentException("portfolio impact basis is required");
            }
        }
    }

    /** Practice and Real are deliberately separate fields; this type exposes no netted total. */
    public record PortfolioImpacts(PortfolioImpact practice, PortfolioImpact real, List<String> notes) {
        public PortfolioImpacts {
            if (practice != null && practice.lane() != PositionDomain.ExecutionLane.PRACTICE) {
                throw new IllegalArgumentException("practice impact must carry the PRACTICE lane");
            }
            if (real != null && real.lane() != PositionDomain.ExecutionLane.REAL) {
                throw new IllegalArgumentException("real impact must carry the REAL lane");
            }
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }
}
