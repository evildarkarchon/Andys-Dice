package io.github.evildarkarchon.andysdice.core.expression;

/**
 * A trailing {@code adv} or {@code dis}: Advantage or Disadvantage. It rewrites the expression's one added
 * {@code 1d20} into {@code 2d20kh1} or {@code 2d20kl1}, which then counts 2 Dice towards the Dice-per-Throw cap.
 */
public enum Advantage {
    /** {@code adv}: roll 2d20 and keep the higher. */
    ADVANTAGE("adv", "advantage", KeepRule.Direction.HIGHEST),
    /** {@code dis}: roll 2d20 and keep the lower. */
    DISADVANTAGE("dis", "disadvantage", KeepRule.Direction.LOWEST);

    private final String keyword;
    private final String longForm;
    private final KeepRule.Direction keeps;

    Advantage(String keyword, String longForm, KeepRule.Direction keeps) {
        this.keyword = keyword;
        this.longForm = longForm;
        this.keeps = keeps;
    }

    /** Returns the keyword as the grammar writes it: {@code adv} or {@code dis}. */
    public String keyword() {
        return keyword;
    }

    /**
     * Returns the word spelt out, {@code advantage} or {@code disadvantage}. The grammar doesn't accept it; the
     * parser recognises it only to suggest the short form.
     */
    public String longForm() {
        return longForm;
    }

    /** Returns the keep rule the rewritten {@code 2d20} gets: {@code kh1} or {@code kl1}. */
    public KeepRule keepRule() {
        return new KeepRule(keeps, 1);
    }
}
