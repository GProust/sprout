# BDR-20. A solid meal says what was in it, and each food keeps its own count

Date: 2026-10-17
Type: Product / Domain

## Status

Accepted

## Context

Sprout has logged solids since the start: a feed of type *solid*, with an
optional weight in grams since schema 15, counted by the statistics and printed
in the doctor's record. It says *that* the baby ate and roughly how much. It does
not say *what*.

Starting solids — *la diversification alimentaire* — is mostly a question about
what. Parents keep a list, often on paper stuck to the fridge: which foods the
baby has met, when the first time was, and how many times since. The count is
the point of the list. A food refused at the first spoon is very often accepted
after it has been offered several more times, so "she doesn't like courgette"
after two tries and "we've offered courgette eleven times" are different
situations, and only a count tells them apart. The other half of the list is the
first time: a parent who wants to see one new food at a time, a few days apart,
needs to know when the last new one was.

What a parent wants to note about each food at a meal is small and always the
same: whether it was a taste — a lick of a spoon, a piece to explore — or a real
serving, and, if they noticed, how it went.

Three things make this easy to get wrong.

**The meal already exists.** A solid feed is a meal. A second, separate log of
"foods given" would mean entering the same lunch twice, on two screens, with two
times that can disagree — and the statistics, the reminder and the doctor's
record would only ever see one of them.

**A list of foods can turn into advice.** Which foods first, at what age, in what
order, and when the common allergens should come in are questions national
guidance answers differently, and has reversed within a decade — peanut and egg
went from "wait" to "don't wait" in most countries. A list that carried any of
that would read as a curriculum. And yet typing "carotte" at every meal, on a
phone held in one hand, is the friction that makes a log stop being kept; and a
household with one phone in French and one in English needs *carotte* and
*carrot* to be one food.

**A count invites a target.** "Offered 6 times" is one step from "6 of 10", a
progress bar, a nudge to try again tomorrow — the app deciding what the right
number is, about a child it cannot see.

## Decision

**A solid meal lists the foods that were in it, and the *First foods* page reads
those lists back, one food at a time.** There is no second log. Logging a solid
meal — from the feeding screen or from the new page, which open the same form —
gains a list of foods; each food on that list is one *offer* of that food to that
baby.

**Each food at a meal records two things, one of them optional:**

| | |
|---|---|
| **A taste or a serving** | Always one or the other. A newly named food starts as *a taste*; a food the household has logged before starts as whatever it was last time. Either is one tap to change. |
| **How it went** | *Liked it*, *Not sure*, *Refused* — or nothing, which is "not noted", not a fourth answer. Tapping the chosen one again clears it. |

**The quantity stays the meal's, and stays optional.** What gets weighed, when
anything is, is the bowl: a purée of three vegetables is not weighed three times.
The meal keeps its one optional weight in grams, which the statistics already
add up, and a food on the list carries no weight of its own.

**The page counts, per food: how many meals included it, how many of those were
tastes and how many servings, how each was received when that was noted, and the
first and the last time.** A food appears the first time it is logged and is
ordered by the most recent offer, or alphabetically, as the parent picks. Each
food opens onto the meals it was part of, and each of those meals opens the meal
itself to correct.

**A taste counts.** It is an offer: the baby met the food. The page shows the
tastes and the servings apart, so nothing is hidden by counting them together.

**Sprout ships a list of plain names, and nothing about them.** Vegetables, meat
and fish — the foods every family names — translated into the app's seven
languages, searchable from the one field in the form (accents optional, so
*epinards* finds *épinards*) and browsable shelf by shelf. Each is a name and
nothing else: no age at which it becomes "allowed", no order of introduction, no
allergen mark, no badge, and nothing suggested next. The shelves are sorted
alphabetically in the phone's language, which is an order that says nothing.

**Anything else is the household's own.** A ratatouille, a grandmother's soup, a
fruit: typed once, it is added as written and offered back one tap away from then
on, for every baby in the household, alongside the list. The list is a
convenience for the common names, not the edge of what can be logged.

**A food from the list is its id; any other food is its name.** A food picked from
the list carries its id on the list (`ref`) as well as the name it was shown
under, so *Carotte* logged in French and *Carrot* logged in English are one
carrot on both phones, each showing it in its own language. Typing a list food's
name out in full picks the list food. A food the household added is its name,
without regard to case or spacing: "Ratatouille" and " ratatouille " are one
food, shown as it was most recently written; "carrot" and "carrots" are two,
because Sprout does not know the language well enough to say otherwise. A food
the household typed is never silently turned into a list food after the fact.

**Nothing on the page is a target, and nothing is graded.** There is no number of
offers the app considers enough, no progress toward one, no reminder to offer a
food again, and no badge. *Refused* has its own icon and its own word and no
warning colour; it is a fact about one meal. The order is by recency or by name,
never by how well a food is going.

**"How it went" is not a reaction.** There is no allergy field, no symptom
checklist and no "reaction" flag. A rash after the first egg is something to
tell a doctor about, and a box in a feeding log would be a clinical sign Sprout
then had to do something with — warn, colour, remember — when the only right
thing it could do is tell the parent to ask someone qualified. The meal's note is
where a parent writes down what they saw, and it stays with that meal.

**Per baby, and shared.** The foods belong to the meal, so they belong to its
baby, travel between the household's phones with it and are deleted with it. The
suggestions in the form are the household's: twins starting solids together are
offered the same carrot.

**The page is always in the grid.** It is a tile among the logs, beside the
medicines, for every baby. Showing it only from a certain age would be the app
deciding when diversification starts, which is the prescriber's call and not a
setting.

## Consequences

- **One more column, and no new table.** `foods` on `feeding`: Room migration
  18 → 19 and an iOS migration to match, defaulting to empty, so every meal
  logged before reads back as a meal that listed nothing. On the wire it is a
  `foods` array on a feeding row, absent when empty — the first field in the
  replica that is an array of objects rather than a packed string, because a
  parent's own words can contain any character a packing could choose. Its
  vectors land in the same commit ([spec](../../spec/README.md)).
- **The list's ids are part of the format.** Both apps hold the same ids in the
  same order, pinned by `spec/vectors/food-catalog.json`; the names are Android's
  string resources, which the iOS catalog is generated from. An id is added to
  the end of a shelf and never renamed or removed, because it is in every meal
  that used it. A phone that meets an id it does not know shows the name the
  meal was saved with.
- **A taste is a feed.** Logging a taste of banana writes a solid meal, and a
  solid meal has always moved the dashboard's "last feed" and the feeding
  reminder. That is unchanged here and deliberate: from the dashboard's point of
  view the baby did eat. A household that finds it wrong for a taste is telling
  us something worth a decision of its own.
- **Spelling is the parent's to keep consistent, for the foods they add.** Two
  spellings of one added food are two lines on the page until the parent corrects
  a meal; the form's suggestions keep that rare. A carrot typed in by hand before
  it was picked from the list stays its own line — merging it would be the app
  deciding that two things the parent logged differently were the same.
- **A list is something to keep honest.** Every name on it is a translation that
  can be wrong in one language, and it will be asked to grow. It grows by names
  only: a shelf of fruit or of cereals is the same decision, a field that says
  anything *about* a food is not.
- **Editing a meal keeps its identity.** The feeding form used to rebuild the
  row it edited, with a new `uid`; the other phone then saw a new meal beside the
  old one instead of a correction. The form now carries the `uid` through, which
  matters more now that a meal holds a list a parent may come back to.
- **The doctor's record is unchanged by this decision.** The workbook already
  carries each solid meal and its grams; adding the foods to it, and deciding
  what a printed page of "refused" implies, is a separate change with its own
  thinking to do — as it was for as-needed medicine
  ([BDR-15](0015-medicine-given-when-needed-and-the-wait-between-doses.md)).

## Alternatives considered

- **A separate food log**, a `food` table and a table of exposures pointing at
  it. It would have made "carrot" a row with an identity, which sounds tidier
  and is worse twice over: the meal would be entered twice, once as a feed and
  once as foods; and two phones naming carrot on the same day would create two
  carrots with two uids, which the merge cannot know are one. Keyed by name, they
  are one food the moment they meet.
- **No list at all**, the household's own foods only. It was this record's first
  draft, and the parent who asked for the feature asked for the list: typing every
  food the first time is real friction, and without shared ids a household on two
  languages counts one carrot twice. What made the list risky was never the names
  — it was ages, order and allergen marks, which it does not carry.
- **A list with recommended ages, or allergens flagged.** That is the version that
  is advice with a date on it, wrong in some country on the day it shipped.
- **Keying list foods by their name** rather than an id. It would split every
  bilingual household's carrots in two, and break every count the day a
  translation is corrected.
- **A weight per food.** Nobody weighs the carrot and the parsnip in one purée
  separately; a per-food field would mostly stay empty, and when one was filled
  in it would sit beside a meal total it does not add up to. The meal's one
  weight is what parents actually have.
- **A target number of offers**, with progress toward it. The number usually
  quoted is a range, about children in general, and a parent whose child accepts
  a food at the third offer or never has done nothing wrong. A count is a fact;
  a target is advice.
- **An allergy or reaction field.** See above: a field invites the app to act on
  what is entered in it, and the only right action belongs to a clinician.
- **Hiding the tile until the baby is four or six months old.** The age is
  guidance that differs by country and by child, and a parent starting early on
  a doctor's advice would find the page missing.
