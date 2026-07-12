package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.model.NewsItem;

import java.util.List;

/** News headlines and/or regulatory filings for a symbol. */
public interface NewsFilingsProvider {

    String name();

    List<NewsItem> news(String symbol);
}
