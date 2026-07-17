/* Program ONE Learn: a searchable view over the existing education registries. */
(function () {
  'use strict';

  var el = UI.el;
  var SOURCE = Object.freeze({
    vocabulary: { label: 'Product vocabulary', css: 'learn-source-vocabulary' },
    info: { label: 'Explanation registry', css: 'learn-source-info' },
    glossary: { label: 'Options glossary', css: 'learn-source-glossary' }
  });

  function normalized(value) {
    return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
  }

  function topicLabel(key, record) {
    if (record && record.label) return record.label;
    var words = String(key || '')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/[_-]+/g, ' ').trim();
    words = words.replace(/\b(pop|ev|iv|hv|cvar|dte|roc|nlv|atm)\b/gi,
      function (word) { return word.toUpperCase(); });
    return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Explanation';
  }

  function sameText(a, b) {
    return normalized(a) && normalized(a) === normalized(b);
  }

  /**
   * Merge by registry aliases, not by copied content. VOCABULARY entries already project into
   * INFO; GLOSSARY also overlaps a few INFO keys. One concept card retains every source badge
   * and every distinct registry-owned definition without pretending those are separate terms.
   */
  function conceptLibrary() {
    var concepts = [];
    var aliases = Object.create(null);

    function remember(item, alias) {
      var key = normalized(alias);
      if (key) aliases[key] = item;
    }

    function addSource(item, source) {
      if (item.sources.indexOf(source) < 0) item.sources.push(source);
    }

    Object.keys(Learn.VOCABULARY || {}).sort().forEach(function (key) {
      var record = Learn.VOCABULARY[key];
      var item = {
        id: 'vocabulary-' + key,
        key: key,
        infoKey: record.infoKey || key,
        label: record.label || topicLabel(key, record),
        short: record.short || '',
        beginner: record.beginner || '',
        expert: record.expert || '',
        glossary: '',
        sources: ['vocabulary']
      };
      concepts.push(item);
      remember(item, key);
      remember(item, item.infoKey);
      remember(item, item.label);
    });

    Object.keys(Learn.INFO || {}).sort().forEach(function (key) {
      var record = Learn.INFO[key] || {};
      var item = aliases[normalized(key)];
      if (!item) {
        item = {
          id: 'info-' + key,
          key: key,
          infoKey: key,
          label: topicLabel(key, record),
          short: record.short || '',
          beginner: record.beginner || '',
          expert: record.expert || '',
          glossary: '',
          sources: []
        };
        concepts.push(item);
      }
      addSource(item, 'info');
      // INFO is authoritative for level depth. The assignments are references to the registry
      // values, not a second copy of the educational content.
      item.short = record.short || item.short;
      item.beginner = record.beginner || item.beginner;
      item.expert = record.expert || item.expert;
      remember(item, key);
      remember(item, item.label);
    });

    Object.keys(Learn.GLOSSARY || {}).sort().forEach(function (key) {
      var definition = Learn.GLOSSARY[key];
      var glossaryAlias = normalized(key);
      var item = aliases[glossaryAlias];
      // A few glossary labels are deliberately shorter than the canonical product label
      // ("max loss" vs "Theoretical max loss"). Merge an unambiguous suffix match so Learn
      // does not manufacture a second journey to the same concept.
      if (!item && glossaryAlias.length >= 6) {
        var suffixMatches = concepts.filter(function (candidate) {
          var labelAlias = normalized(candidate.label);
          return labelAlias.endsWith(glossaryAlias) || glossaryAlias.endsWith(labelAlias);
        });
        if (suffixMatches.length === 1) item = suffixMatches[0];
      }
      if (!item) {
        item = {
          id: 'glossary-' + normalized(key),
          key: key,
          infoKey: null,
          label: topicLabel(key),
          short: definition || '',
          beginner: '',
          expert: '',
          glossary: '',
          sources: []
        };
        concepts.push(item);
      } else if (!sameText(definition, item.short)
          && !sameText(definition, item.beginner) && !sameText(definition, item.expert)) {
        item.glossary = definition;
      }
      addSource(item, 'glossary');
      remember(item, key);
      remember(item, item.label);
    });

    return concepts.sort(function (a, b) { return a.label.localeCompare(b.label); });
  }

  function sourceBadge(source) {
    var meta = SOURCE[source];
    return el('span', { class: 'learn-source ' + meta.css, 'data-source': source }, meta.label);
  }

  function conceptCard(item, level) {
    var detail = item[level] || '';
    var search = [item.key, item.infoKey, item.label, item.short, item.beginner,
      item.expert, item.glossary, item.sources.join(' ')].join(' ').toLowerCase();
    var card = el('article', {
      class: 'learn-concept-card',
      'data-learn-kind': 'concept',
      'data-provenance': item.sources.join(' '),
      'data-search': search
    },
    el('div', { class: 'learn-card-heading' },
      el('h3', {}, item.label),
      el('div', { class: 'learn-source-row', 'aria-label': 'Definition sources' },
        item.sources.map(sourceBadge))),
    item.short ? el('p', { class: 'learn-short' }, item.short) : null,
    detail && !sameText(detail, item.short)
      ? el('p', { class: 'learn-depth', 'data-level-depth': level }, detail) : null,
    item.glossary ? el('p', { class: 'learn-glossary-definition' },
      el('b', {}, 'Glossary definition: '), item.glossary) : null);
    if (level === 'expert') {
      card.appendChild(el('div', { class: 'learn-registry-key' },
        'Registry key ', el('code', {}, item.infoKey || item.key)));
    }
    return card;
  }

  function strategyDestination() {
    var active = window.PlanStore && PlanStore.active ? PlanStore.active() : null;
    if (active && active.status === 'ACTIVE') {
      return { href: PlanStore.path(active, 'STRATEGY'), label: 'Open ' + active.symbol + ' Plan strategy' };
    }
    return { href: '#/research', label: 'Choose a symbol in Research' };
  }

  function strategyCard(key, guide, level) {
    var label = window.ViewShared && ViewShared.prettyStrategy
      ? ViewShared.prettyStrategy(key) : topicLabel(key);
    var destination = strategyDestination();
    var how = Array.isArray(guide.how) ? guide.how : [];
    var search = [key, label, guide.story, guide.win, guide.lose, guide.watch]
      .concat(how).join(' ').toLowerCase();
    var mechanics = el('ol', { class: 'learn-mechanics' }, how.map(function (step) {
      return el('li', {}, step);
    }));
    var mechanicsBlock = level === 'beginner'
      ? el('details', { class: 'learn-mechanics-disclosure' },
          el('summary', {}, 'How it works'), mechanics)
      : el('div', { class: 'learn-mechanics-block' }, el('h4', {}, 'Mechanics'), mechanics);
    return el('article', {
      class: 'learn-strategy-card',
      'data-learn-kind': 'strategy',
      'data-strategy-guide': key,
      'data-search': search
    },
    el('div', { class: 'learn-strategy-head' },
      el('div', {}, el('span', { class: 'learn-source learn-source-strategy' }, 'Strategy guide'),
        el('h3', {}, label)),
      level === 'expert' ? el('code', {}, key) : null),
    el('p', { class: 'learn-strategy-story' }, guide.story),
    mechanicsBlock,
    el('div', { class: 'learn-strategy-outcomes' },
      el('div', { class: 'learn-outcome learn-outcome-win' }, el('h4', {}, 'Works when'), el('p', {}, guide.win)),
      el('div', { class: 'learn-outcome learn-outcome-loss' }, el('h4', {}, 'Loses when'), el('p', {}, guide.lose)),
      el('div', { class: 'learn-outcome learn-outcome-watch' }, el('h4', {}, 'Watch'), el('p', {}, guide.watch))),
    el('div', { class: 'learn-strategy-command' },
      el('a', {
        class: 'btn btn-secondary learn-use-plan',
        href: destination.href,
        'data-strategy': key,
        'aria-label': 'Use ' + label + ' in a Plan. ' + destination.label,
        title: destination.label + '. The canonical Strategy catalog remains the decision owner.'
      }, 'Use this in a Plan'),
      el('span', { class: 'muted small' }, destination.label)));
  }

  function coverageStat(value, label, source) {
    return el('div', { class: 'learn-coverage-stat', 'data-coverage-source': source },
      el('b', {}, String(value)), el('span', {}, label));
  }

  function route(root) {
    var level = Learn.currentLevel();
    var concepts = conceptLibrary();
    var strategyKeys = Object.keys(Learn.STRATEGY_GUIDE || {}).sort();
    var counts = {
      vocabulary: Object.keys(Learn.VOCABULARY || {}).length,
      info: Object.keys(Learn.INFO || {}).length,
      glossary: Object.keys(Learn.GLOSSARY || {}).length,
      concepts: concepts.length,
      strategies: strategyKeys.length
    };

    var page = el('div', {
      class: 'learn-page',
      id: 'learn-page',
      'data-vocabulary-count': counts.vocabulary,
      'data-info-count': counts.info,
      'data-glossary-count': counts.glossary,
      'data-concept-count': counts.concepts,
      'data-strategy-count': counts.strategies
    });
    root.appendChild(page);

    page.appendChild(el('header', { class: 'learn-hero' },
      el('div', {}, el('div', { class: 'eyebrow' }, 'LEARN · ONE SHARED EXPLANATION SYSTEM'),
        el('h1', {}, 'Understand the language, then use it'),
        el('p', {}, 'Search the same definitions and strategy guides used throughout StrikeBench. '
          + 'Your experience level changes the depth, never the meaning.')),
      el('div', { class: 'learn-coverage', 'aria-label': 'Learning registry coverage' },
        coverageStat(counts.concepts, 'searchable concepts', 'concepts'),
        coverageStat(counts.strategies, 'strategy guides', 'strategies'),
        coverageStat(counts.vocabulary, 'product terms', 'vocabulary'),
        coverageStat(counts.glossary, 'glossary terms', 'glossary'))));

    var search = el('input', {
      id: 'learn-search', type: 'search', autocomplete: 'off',
      placeholder: 'Search assignment, volatility, spreads…',
      'aria-describedby': 'learn-search-help learn-results-status'
    });
    var filters = [
      ['all', 'Everything'], ['vocabulary', 'Product vocabulary'],
      ['info', 'Explanations'], ['glossary', 'Glossary'], ['strategy', 'Strategies']
    ];
    var activeFilter = 'all';
    var filterButtons = filters.map(function (filter) {
      return el('button', {
        type: 'button', class: 'learn-filter', 'data-learn-filter': filter[0],
        'aria-pressed': String(filter[0] === activeFilter)
      }, filter[1]);
    });
    var status = el('p', { id: 'learn-results-status', class: 'learn-results-status',
      role: 'status', 'aria-live': 'polite' });
    var noResults = el('div', { class: 'empty-state learn-no-results', hidden: true },
      el('h3', {}, 'No matching learning topics'),
      el('p', {}, 'Try a broader word or show every source.'));
    var conceptGrid = el('div', { class: 'learn-concept-grid', id: 'learn-concept-grid' },
      concepts.map(function (item) { return conceptCard(item, level); }));
    var strategyGrid = el('div', { class: 'learn-strategy-grid', id: 'learn-strategy-grid' },
      strategyKeys.map(function (key) { return strategyCard(key, Learn.STRATEGY_GUIDE[key], level); }));
    var conceptSection = el('section', { class: 'learn-section', id: 'learn-concepts' },
      el('div', { class: 'learn-section-head' }, el('div', {}, el('h2', {}, 'Concepts'),
        el('p', { class: 'muted' }, 'Canonical terms, deeper explanations, and options definitions in one index.'))),
      conceptGrid);
    var strategySection = el('section', { class: 'learn-section', id: 'learn-strategies' },
      el('div', { class: 'learn-section-head' }, el('div', {}, el('h2', {}, 'Strategy guides'),
        el('p', { class: 'muted' }, 'Payoff stories from the shared guide; the Plan Strategy catalog remains authoritative.'))),
      strategyGrid);

    var rail = el('aside', { class: 'learn-rail', 'aria-label': 'Learn filters and provenance' },
      el('div', { class: 'learn-search-block' },
        el('label', { for: 'learn-search' }, 'Find a concept or strategy'), search,
        el('p', { id: 'learn-search-help', class: 'muted small' }, 'Searches titles, definitions, mechanics, and risks.')),
      el('div', { class: 'learn-filter-group', role: 'group', 'aria-label': 'Filter learning sources' },
        filterButtons),
      status,
      el('div', { class: 'learn-provenance-legend' },
        el('h2', {}, 'Where each definition comes from'),
        el('p', {}, sourceBadge('vocabulary'), ' canonical product labels'),
        el('p', {}, sourceBadge('info'), ' level-specific explanation registry'),
        el('p', {}, sourceBadge('glossary'), ' options terms of art')),
      el('p', { class: 'learn-coverage-note' },
        counts.vocabulary + ' product vocabulary entries + ' + counts.info + ' explanation topics + '
          + counts.glossary + ' glossary terms resolve to ' + counts.concepts
          + ' distinct searchable concepts. Overlaps stay one concept with multiple source badges.'));
    var results = el('div', { class: 'learn-results' }, conceptSection, strategySection, noResults);
    page.appendChild(el('div', { class: 'learn-layout' }, rail, results));

    var cards = Array.from(page.querySelectorAll('[data-learn-kind]'));
    function applyFilters() {
      var query = search.value.trim().toLowerCase();
      var conceptVisible = 0;
      var strategyVisible = 0;
      cards.forEach(function (card) {
        var kind = card.getAttribute('data-learn-kind');
        var provenance = (card.getAttribute('data-provenance') || '').split(/\s+/);
        var categoryMatch = activeFilter === 'all'
          || (activeFilter === 'strategy' && kind === 'strategy')
          || (kind === 'concept' && provenance.indexOf(activeFilter) >= 0);
        var queryMatch = !query || (card.getAttribute('data-search') || '').indexOf(query) >= 0;
        card.hidden = !(categoryMatch && queryMatch);
        if (!card.hidden && kind === 'concept') conceptVisible++;
        if (!card.hidden && kind === 'strategy') strategyVisible++;
      });
      conceptSection.hidden = conceptVisible === 0;
      strategySection.hidden = strategyVisible === 0;
      noResults.hidden = conceptVisible + strategyVisible > 0;
      status.textContent = (conceptVisible + strategyVisible) + ' results · '
        + conceptVisible + ' concepts · ' + strategyVisible + ' strategies';
    }
    search.addEventListener('input', applyFilters);
    filterButtons.forEach(function (button) {
      button.addEventListener('click', function () {
        activeFilter = button.getAttribute('data-learn-filter');
        filterButtons.forEach(function (candidate) {
          candidate.setAttribute('aria-pressed', String(candidate === button));
        });
        applyFilters();
      });
    });
    applyFilters();
  }

  window.ViewLearn = Object.freeze({ route: route, concepts: conceptLibrary });
})();
