# BDR-26. Help is on every page, on demand, and it explains the app, never the baby

Date: 2026-10-22
Type: Product / UX

## Status

Accepted

## Context

Sprout explains itself in scattered sentences. The pumping page gives a rough
guide to how long milk keeps, the sharing page opens on what a household is,
the export screen says what a doctor will be handed, and a handful of fields
carry a hint. Each was written for the one moment it sits beside.

Some of what surprises parents has no sentence anywhere:

- **A break is not time at the breast**
  ([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md)). The clock stops while
  the baby is winded, and a parent who did not expect that thinks the timer is
  broken.
- **"No set gap"** on a medicine
  ([BDR-18](0018-a-medicine-measured-by-amount.md)) means the leaflet gave no
  wait at all, not a wait of zero.
- **Tastes and servings** are counted apart on First foods, and a taste still
  counts as an offer ([BDR-20](0020-a-solid-meal-says-what-was-in-it.md)).
- **Removing a phone changes the household's key**, so every phone that stays
  needs a fresh invitation
  ([ADR-0009](../adr/0009-the-household-is-a-group-not-a-pair.md)). The
  confirmation says so, but only once the parent has already decided to do it.

The usual fix is the one this app has refused twice. A tour or a tip at launch
stands between a parent and the reason they opened the app, which is what
[BDR-6](0006-check-in-waits-on-the-dashboard.md) took out; and what is new after
an update is a card the parent puts away, never a screen
([BDR-22](0022-whats-new-is-a-card-the-parent-puts-away.md)).

Two more things make help easy to get wrong.

- **Explaining a screen is close to advising on a baby.** The page about a
  medicine is one sentence from saying how long to wait between doses, and the
  page about first foods one sentence from saying which food to try next. Each
  page's record already says what the page must never say, and help is more
  words on that page.
- **Two apps.** A household with one phone of each will compare them, and
  help that says more on one, or describes a button the other does not have,
  is help that is wrong half the time.

## Decision

**Every page has a ? in its top bar, and help opens from there and from
nowhere else.**

- **The button.** A question-mark icon at the trailing end of the top bar, the
  same place on every page.
  - It is labelled "Help" for TalkBack and VoiceOver.
  - On iOS it is identified as `help-<page>`, so a UI test finds it in any
    language.
  - The dashboard already has the babies and the settings in that corner; the ?
    goes after them.
- **Never shown on its own.** No first-launch prompt, no badge, no dot, no "did
  you know", no tooltip that appears after the third visit. A parent who never
  taps the ? never sees help, and loses nothing by it.
- **The pages.** Home, the baby's own tab, Trends, You, Feeding (and the live
  timer), Pumping, Sleep, Nappies, Growth, Treatments, As needed, First foods,
  Wellbeing (and the check-in, which shares its sheet), Babies, Household
  sharing, the record for the doctor, and Settings. Seventeen sheets.

**A sheet is text only, in up to three sections.**

- *What this page is for*: a sentence or two.
- *How to use it*: the main actions, in the order a parent meets them.
- *Good to know*: what surprises people — what travels to the other phones,
  what is kept when something is deleted, what goes into the doctor's record.

The three headings are shared by every page, and each is a heading to the
screen reader so a parent listening can jump to one. A page may leave out a
section it has nothing for, as long as it leaves out the same one on both
apps. There are no screenshots, animations or links in a sheet:

- a picture of the page would have to be taken again in seven languages and two
  themes every time the page changed, and the page is right there behind the
  sheet;
- a link would lead out of the app to help that needs a browser, a network and
  a second copy to keep true.

**Help explains the app, never the baby.** Every rule that applies to a page
applies to its help, and the help for these pages keeps them in particular:

- **As needed** ([BDR-15](0015-medicine-given-when-needed-and-the-wait-between-doses.md),
  [BDR-18](0018-a-medicine-measured-by-amount.md)): no medicine named, no dose,
  no usual interval. The traffic light is described as arithmetic on the
  parent's own figures, and "No set gap" as no gap rule, not a gap of zero.
- **First foods and allergies** ([BDR-20](0020-a-solid-meal-says-what-was-in-it.md),
  [BDR-21](0021-a-babys-allergies-as-the-household-knows-them.md)): no ages, no
  order of introduction, nothing about which foods are allergens. It says the
  list carries none of that.
- **Trends and the record for the doctor** ([BDR-8](0008-statistics-and-the-who-growth-curves.md),
  [BDR-12](0012-a-record-for-the-doctor.md)): what the band and the span are,
  never what a centile means for a baby.
- **Feeding** ([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md),
  [BDR-19](0019-two-breastfeeds-saved-apart-can-be-joined.md)): a break is not
  time at the breast, and joining is offered, never done for you.
- **Sharing** ([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)
  to [ADR-0011](../adr/0011-what-survives-a-new-phone.md)): what travels, what
  never does, and how removing a phone works. Help changes no behaviour here.

**Help describes what the page does, in the page's own words.**

- Controls are named by their labels, in each language's own quotation marks,
  so the words in the sheet are the words on the screen.
- A sheet only says what both apps do. Where one phone can do something the
  other cannot yet — the automatic exchange over Bluetooth, which iOS does not
  have — it says "where this phone offers it" rather than promising it.
- When a page changes what it does, its help changes in the same pull request,
  like the page's other text.

**One list of pages and sections, on both apps.**

- Android: `ui/help/HelpPages.kt`. iOS: `Screens/Help/HelpPages.swift`.
- Both are written in the same order, and each side's checks read the other's
  list:
  - Android's `HelpPagesTest` holds every page to opening on what it is for,
    every text to its `help_<page>_<section>` name and to a translation in all
    seven languages, and the Swift list to the Kotlin one.
  - iOS's `check_string_keys.py` fails on a key missing from the catalog, and
    compares the Swift list with the Kotlin one.
- The text is `help_<page>_<section>` in Android's strings in all seven
  languages, generated into iOS's like every other string. The headings are
  `help_section_purpose`, `help_section_how` and `help_section_good_to_know`,
  and a sheet's title is the page's own name.

## Consequences

- The behaviours that surprised people are written down once, where a parent
  can find them, and asked for rather than pushed. A parent who never taps the ?
  has exactly the app they had before, plus one icon per page.
- **Fifty-one paragraphs in seven languages** are now part of the app, and they
  go stale silently: nothing fails when a page changes and its help does not.
  The rule above, and the CLAUDE.md section beside it, are the only guard.
- **A page's record now covers its help.** A review of a change to the
  medicines page reads its help against BDR-15 and BDR-18 like any other
  sentence on it.
- The existing sentences stay where they are. A hint beside a field answers the
  question at the moment it is asked, and the ? is for everything else.
- Every screenshot gains an icon in its top bar, and each app captures one help
  sheet, As needed's — the page whose help says most about what Sprout does
  not do.
- Nothing about help is stored or synced, and nothing counts whether a sheet
  was read. There is no way to know which help is read, and that is accepted:
  finding out would need the analytics Sprout does not have.
- A guided tour of the app is a decision of its own (#110). Nothing here
  decides it; what this record settles is that each page's help is there when
  asked for, and only then.

## Alternatives considered

- **More inline hints.** The scattered sentences are good where they are, but a
  hint on every behaviour would bury the pages. The parent at 3 a.m. needs the
  buttons, not the paragraph about them.
- **A help page in Settings, or an FAQ.** One place for everything, and the
  furthest place from the question. The page a parent is puzzled by is the page
  they are on.
- **Help on a website.** It would need a browser and a network, and Sprout has
  no INTERNET permission. It would also be one more copy of the text to keep
  true, in one language more often than seven.
- **Help that opens on its own** on a page's first visit, or a tip pointing at
  the ?. It stands in front of the page at exactly the moment the parent came to
  use it, which is the interruption BDR-6 removed.
- **Screenshots in the sheets.** Clearer for some things. Seven languages and
  two themes, re-shot on every change, and wrong the day a page is re-laid.
- **A title and headings per page.** More strings to translate for no gain: the
  headings say the same thing on every page, and the page already has a name.
