package io.liftandshift.market.ports;

import java.util.OptionalDouble;

/** Risk-free interest rates for pricing models. */
public interface RatesProvider {

    String name();

    /** Annualized continuously-usable rate (e.g. 0.043) for a horizon of the given days. */
    OptionalDouble riskFreeRate(int days);
}
