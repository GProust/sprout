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

**Every list of foods is advice.** Which foods first, at what age, in what order,
and when the common allergens should come in are questions national guidance
answers differently, and has reversed within a decade — peanut and egg went from
"wait" to "don't wait" in most countries. A list in the app, however neutral it
means to be, reads as a curriculum: the foods it shows are the ones to do, and
the ones it leaves out are the ones to wait for.

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

**Sprout ships no list of foods.** No starter list, no allergen checklist, no
age at which a food becomes "allowed", no order of introduction, and no
suggestion of what to try next. The foods are the parent's own words. After the
first time, a food is one tap away: the form offers every food the household has
logged before, most recent first, and filters them as the parent types — so the
list a family builds is the only list there is.

**A food is its name, without regard to case or spacing.** "Carrot", "carrot"
and " carrot " are one food; the page shows it as it was most recently written.
"Carrot" and "carrots" are two, because Sprout does not know the language well
enough to say otherwise and guessing would merge foods a parent meant apart.

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
- **A taste is a feed.** Logging a taste of banana writes a solid meal, and a
  solid meal has always moved the dashboard's "last feed" and the feeding
  reminder. That is unchanged here and deliberate: from the dashboard's point of
  view the baby did eat. A household that finds it wrong for a taste is telling
  us something worth a decision of its own.
- **Spelling is the parent's to keep consistent.** Two spellings of one food are
  two lines on the page until the parent corrects a meal. The form's
  suggestions are what keep that rare: the second time, nobody types.
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
- **Shipping a list of common first foods**, translated, to tap from on day one.
  It saves a few minutes of typing in the first week and costs the decision
  above: every list is an order and an omission, it would be wrong in some
  country on the day it shipped, and an allergen list in particular is advice
  with a date on it. The household's own list fills in by the second week.
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
