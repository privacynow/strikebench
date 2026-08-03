package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.position.PositionDomain;

/** Existing account exposure supplied to one hypothetical-position assessment. */
public record PortfolioExposureContext(
        PositionDomain.BookType bookType,
        long grossDollarDeltaCents,
        long netDollarDeltaCents,
        long symbolGrossDollarDeltaCents,
        boolean complete,
        String basis
) {
    public PortfolioExposureContext {
        if (bookType == null || bookType == PositionDomain.BookType.NONE) {
            throw new IllegalArgumentException("portfolio exposure requires a concrete book type");
        }
        if (grossDollarDeltaCents < 0 || symbolGrossDollarDeltaCents < 0
                || symbolGrossDollarDeltaCents > grossDollarDeltaCents) {
            throw new IllegalArgumentException("portfolio gross exposure is inconsistent");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("portfolio exposure basis is required");
        }
    }
}
