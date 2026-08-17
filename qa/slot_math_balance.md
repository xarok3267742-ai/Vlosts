# V Slot Math Balance

Exact enumeration over all configured reel-strip stop combinations.

## 2026-08-07

Configuration:
- 5 reels
- 3 visible rows
- 1..10 selectable active paylines
- 24 stop positions per reel
- 7,962,624 total stop combinations per slot
- +5 free spins for a bonus result
- Retriggerable free spins are included in the all-in RTP calculation

Max-line results after RTP and volatility tuning:

| Slot | Profile | Direct RTP | All-in RTP | Hit rate | Bonus rate | Single-line variance | Max win at line bet 10 / 250 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Neon Nights | Low | 86.2452% | 94.9975% | 36.4334% | 1.6052% | 19.1414 | 3,600 / 90,000 |
| Ocean Pearl | Medium-low | 86.2418% | 94.9984% | 36.9810% | 1.6052% | 21.1046 | 6,970 / 174,250 |
| Violet Fortune | Medium | 86.1379% | 94.9323% | 36.5625% | 1.6052% | 22.9231 | 3,920 / 98,000 |
| Roman Reels | Medium-high | 86.1818% | 94.9922% | 36.6136% | 1.6052% | 26.5641 | 5,170 / 129,250 |
| Pharaoh Gold | High | 86.2228% | 94.9999% | 36.1034% | 1.6052% | 37.6046 | 11,060 / 276,500 |

Line-count validation:
- Unit tests now enumerate every configured stop combination for each selectable active-line count from 1 to 10.
- Every line count stays inside the same 95% all-in RTP tolerance, so lowering lines lowers total stake exposure without making the theoretical return drift.
- Hit rate is expected to rise as more lines are enabled; the test asserts it never falls when active lines increase.
- A separate exact weighted-symbol test locks the intended low-to-high volatility order without requiring a long stop-combination run.
- Exact max-line enumeration covers both paid-spin and free-spin reel windows and locks each slot's maximum single-spin payout at the minimum configured line bet.

Interpretation:
- The previous direct-only 95% table produced about 103.29% all-in RTP once retriggerable free spins were included, which made the virtual coin balance drain too slowly.
- Theme-specific reel rhythms preserve each reel's symbol frequencies and the same bonus rarity while varying multi-line hit correlation; total expected return remains about 95%.
- Neon favors steadier payouts, Ocean and Violet occupy the middle, Roman is more variable, and Pharaoh concentrates more return in rare large combinations.
- The five themes no longer share one payout model or one renamed reel sequence.
- Line selection changes total bet as `line bet × active lines`; free spins still run without coin debit.
- Scatter payouts use `scatter multiplier × total bet` and count matching symbols in any visible positions.
- Paid autospin treats a payout of at least `10× total bet` as a big win and stops before another paid spin.
- These values do not represent real-money odds, cash prizes, purchases, or cash-out value.
