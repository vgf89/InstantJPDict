package com.holopengin.instantjpdict.util

/**
 * Policy for surfacing a character at a CTC blank timestep (#44).
 *
 * The recogniser deletes characters it cannot name — OOV kanji, dropped `。` `、` `ー`
 * — by emitting blank. Deciding when to put a candidate back is a *policy* question,
 * and it was measured rather than guessed: 218 paired bench lines (trails +
 * vert_large) give 17 dropped characters and 6,548 genuine gaps between emitted
 * characters.
 *
 * The 17 deletions fall into two regimes:
 *
 *  - **contest (5)** — the head ranked a confident non-blank alternative just below
 *    blank, e.g. `blank 0.42 / candidate 0.98`. Here surfacing is right about 60%
 *    of the time.
 *  - **confident blank (12)** — blank ≈ 0.999 with the best alternative ≤ 0.01. The
 *    information is not in the distribution at all: no threshold reaches these, and
 *    they need external evidence (dictionary / reading context), not a decode rule.
 *
 * The rule below fires on the 5 contests and on 3 of the 6,548 gaps (0.05%). Because
 * recoveries and spurious insertions are roughly equal in number, enabling it is
 * about *error neutral* on the bench — which is why [DISABLED] is the default and
 * this stays a user-visible choice (the surfaced character carries a gap as a
 * selectable alternative) rather than a silent correction.
 */
object BlankRecovery {

    /** No surfacing. The default: measurement does not justify enabling it globally. */
    const val DISABLED = 0f

    /** A candidate must be this probable on its own to displace a blank at all. */
    const val MIN_CANDIDATE_PROB = 0.5f

    /**
     * Should [candidateProb]'s character be surfaced where the head emitted blank?
     *
     * @param blankProb the blank class's probability at that timestep
     * @param candidateProb the non-blank candidate's probability
     * @param minCandidateProb the caller's floor (0 disables surfacing entirely)
     */
    fun shouldSurface(
        blankProb: Float,
        candidateProb: Float,
        minCandidateProb: Float = MIN_CANDIDATE_PROB,
    ): Boolean {
        if (minCandidateProb <= DISABLED) return false
        // Two conditions: the candidate is confident on its own, and it actually
        // beats the blank call (a contest). A high blank with a weak alternative is
        // the "confident blank" regime above, where surfacing would be a guess.
        return candidateProb >= minCandidateProb && candidateProb > blankProb
    }
}
