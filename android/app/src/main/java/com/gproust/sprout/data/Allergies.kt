package com.gproust.sprout.data

import com.gproust.sprout.data.local.AllergyEntity
import com.gproust.sprout.data.local.MealFood

/**
 * Which food an allergy is about, in the terms the First foods page counts by
 * (BDR-20, BDR-21): the list's id when it has one, its name otherwise. A carrot
 * marked on a phone in French is the carrot on the meals logged in English.
 *
 * Mirrored by `SproutData/Allergies.swift`.
 */
fun allergyIdentity(allergy: AllergyEntity): String = foodIdentity(MealFood(allergy.name, ref = allergy.ref))

/**
 * The identities of the allergies that still stand — what the page and the
 * meal form mark a food with.
 */
fun allergyIdentities(allergies: List<AllergyEntity>): Set<String> =
    allergies.filter { it.deletedAt == null }.map(::allergyIdentity).toSet()
