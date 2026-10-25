package andysdice.prototype.rollui;

import java.util.SplittableRandom;

/**
 * PROTOTYPE smoke check, no window: prints how sample Roll Expressions parse (or fail, with the span drawn under
 * them) and one roll of each, so the messages the UI shows can be eyeballed. Run with
 * {@code -Drollui.main=andysdice.prototype.rollui.ParserCheck}.
 */
final class ParserCheck {

    private ParserCheck() {
    }

    public static void main(String[] args) throws Exception {
        String[] samples = {
                "2d6+1d8+3", "1d20+5 adv", "1d20adv", "4d6kh3", "4d6rr1kh3+2", "2d6ro<=2+5", "d%", "2d%kh1",
                "1d20ro1 adv", "1d4-5", "-1d6+2", "1D20+5 ADV",
                "", "2d6+", "2 d6", "1d7", "dF", "0d6", "4d6dl1", "4d6k3", "4d6kh5", "4d6r1", "4d6rr<4", "1d6ro7",
                "1d6rr<=6", "4d6kh3rr1", "1d20+5 advantage", "2d20 adv", "1d20 adv dis", "adv 1d20", "1d20-1d20 adv",
                "5", "1000", "21d6", "10d% ", "1d20+5 # attack", "2d6*2", "(1d6)", "1d6!", "1d20 dis+3",
        };
        SplittableRandom rng = new SplittableRandom(3);
        for (String s : samples) {
            Expr.Result r = Expr.parse(s);
            if (r.error() != null) {
                System.out.printf("ERR  %-22s %s%n     %-22s%n", "'" + s + "'", r.error().message(), " " + Ui.caretLine(r.error()));
            } else {
                RollPlan p = RollPlan.roll(r.ok(), rng);
                System.out.printf("OK   %-22s dice=%d throws=%d total=%d facts=%s%n", "'" + s + "'", r.ok().physicalDice(),
                        p.throwCount(), p.total, p.facts());
            }
        }
    }
}
