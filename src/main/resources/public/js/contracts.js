/* Product-wide route and horizon contracts. These are semantic constants, not view defaults. */
(function () {
  'use strict';

  var Horizon = {
    table: {
      '0DTE': { sessions: 1, expiryDays: 0 },
      day: { sessions: 1, expiryDays: 0 },
      week: { sessions: 5, expiryDays: 7 },
      month: { sessions: 21, expiryDays: 35 },
      quarter: { sessions: 63, expiryDays: 90 }
    },
    sessions: function (raw, fallback) {
      if (/^\d+d$/i.test(String(raw || ''))) return Math.max(1, parseInt(raw, 10));
      var key = String(raw || '').trim();
      if (key.toLowerCase() === '0dte') key = '0DTE';
      var row = this.table[key];
      return row ? row.sessions : (fallback == null ? this.table.month.sessions : fallback);
    },
    expiryDays: function (raw) {
      var key = String(raw || '').trim();
      if (key.toLowerCase() === '0dte') key = '0DTE';
      var row = this.table[key] || this.table.month;
      return row.expiryDays;
    },
    keyForSessions: function (days) {
      days = Number(days);
      return days <= 1 ? '0DTE' : days <= 10 ? 'week' : days <= 45 ? 'month' : 'quarter';
    }
  };

  var stagePaths = ['understand', 'evidence', 'strategy', 'outcomes', 'decide', 'manage-review'];
  var portfolioTabs = ['construct', 'positions', 'active', 'closed', 'activity', 'record', 'account'];
  var bookTabs = ['overview', 'activity', 'performance', 'tax', 'settings'];
  var dataTabs = ['overview', 'datasets', 'simulation', 'sources', 'admin'];
  var Routes = {
    valid: function (name, args) {
      args = args || [];
      if (name === 'home') return !args.length || (args.length === 1 && args[0] === 'tour');
      if (name === 'research') return !args.length
        || (args.length === 1 && /^[A-Z0-9._-]+$/i.test(args[0]));
      if (name === 'plans') return !args.length;
      if (name === 'plan') return args.length === 2
        && stagePaths.indexOf(String(args[1] || '').split('?')[0]) >= 0;
      if (name === 'portfolio') return !args.length
        || (args.length === 1 && portfolioTabs.indexOf(args[0]) >= 0)
        || (args[0] === 'book' && args.length === 2 && bookTabs.indexOf(args[1]) >= 0)
        || (args[0] === 'trade' && args.length === 2 && /^tr_[A-Za-z0-9_-]+$/.test(args[1]));
      if (name === 'data') return !args.length
        || (args.length === 1 && dataTabs.indexOf(args[0]) >= 0);
      return false;
    },
    canonical: function (hash) {
      hash = hash || '#/home';
      var path = hash.replace(/^#\//, '').split('?')[0];
      var parts = path.split('/').filter(function (part) { return part.length; });
      return this.valid(parts[0] || 'home', parts.slice(1)) ? hash : '#/home';
    },
    navOwner: function (name, args) {
      return name === 'plan' || name === 'plans' ? 'plans' : name;
    }
  };

  window.Product = Object.freeze({ Horizon: Horizon, Routes: Routes });
})();
