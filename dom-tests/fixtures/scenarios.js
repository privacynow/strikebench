'use strict';

/** Wire order and terminal moves of the server-owned ScenarioStory enum. */
const STORIES = Object.freeze([
  Object.freeze({ story: 'MARKET_CRASH', underlyingMovePct: -0.20,
    movePct: -20, ivShiftPoints: 14, elapsedSessions: 5 }),
  Object.freeze({ story: 'GAP_DOWN', underlyingMovePct: -0.09,
    movePct: -9, ivShiftPoints: 8, elapsedSessions: 1 }),
  Object.freeze({ story: 'ORDERLY_PULLBACK', underlyingMovePct: -0.06,
    movePct: -6, ivShiftPoints: 4, elapsedSessions: 3 }),
  Object.freeze({ story: 'CHOPPY_SIDEWAYS', underlyingMovePct: -0.01,
    movePct: -1, ivShiftPoints: 1, elapsedSessions: 5 }),
  Object.freeze({ story: 'FLAT_RANGE', underlyingMovePct: 0,
    movePct: 0, ivShiftPoints: 0, elapsedSessions: 7 }),
  Object.freeze({ story: 'GRIND_HIGHER', underlyingMovePct: 0.06,
    movePct: 6, ivShiftPoints: -2, elapsedSessions: 10 }),
  Object.freeze({ story: 'STRONG_RALLY', underlyingMovePct: 0.13,
    movePct: 13, ivShiftPoints: -4, elapsedSessions: 10 }),
  Object.freeze({ story: 'MELT_UP', underlyingMovePct: 0.20,
    movePct: 20, ivShiftPoints: -6, elapsedSessions: 12 })
]);

const MOVES = Object.freeze(STORIES.map(row => row.underlyingMovePct));

/**
 * A browser request may name a story and leave its numeric controls null. A mocked server must
 * return the resolved ScenarioStory receipt just as the Java owner does; echoing the null request
 * would test a wire shape the packaged application never emits.
 */
function resolveInteraction(interaction) {
  if (!interaction || !interaction.story) return interaction || null;
  const policy = STORIES.find(row => row.story === String(interaction.story));
  if (!policy) return Object.assign({}, interaction);
  const supplied = (field, fallback) =>
    interaction[field] == null ? fallback : Number(interaction[field]);
  return {
    story: policy.story,
    movePct: supplied('movePct', policy.movePct),
    ivShiftPoints: supplied('ivShiftPoints', policy.ivShiftPoints),
    elapsedSessions: supplied('elapsedSessions', policy.elapsedSessions),
    sourcePathIndex: interaction.sourcePathIndex == null
      ? null : Number(interaction.sourcePathIndex)
  };
}

module.exports = { STORIES, MOVES, resolveInteraction };
