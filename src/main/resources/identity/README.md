# Guest identity catalog

Every ORISO guest and every registered asker gets one kind of User-ID: an animal handle such as
`katze_sam_4821`, rolled with the dice. Anonymity rests on that roll, so the server accepts a
guest's User-ID only when the dice engine can produce it (ORISO-Frontend#2346).

## Where the names come from

| File | Role | Edited by hand? |
| --- | --- | --- |
| ORISO-Frontend `src/utils/anonName/data.ts` | **Source.** Animals per avatar set, adjectives, first names per language. | Yes — the only place. |
| ORISO-Frontend `src/components/registration/accountData/registrationUsername.ts` | **Source.** Slug rule (umlauts spelled out, 4-digit suffix, 30 characters). | Yes. |
| `pseudonym-vocabulary.json` | Server copy of the vocabulary; `GeneratedPseudonyms` validates rolled User-IDs against it. | No — generated. |
| `src/test/resources/identity/frontend-generated-handles.json` | Hash of every handle base the Frontend function produces; pins the slug rule. | No — generated. |
| `guest-name-catalog.json` | The server's own roll table (animal → avatar file) for clients that send no User-ID. | Yes, but must match the vocabulary (`GuestIdentityCatalogParityTest`). |

## Adding animals, names or an avatar set

1. Change `data.ts` (and the artwork) in ORISO-Frontend.
2. Regenerate the server copies from that checkout:
   `node scripts/contracts/sync-pseudonym-vocabulary.mjs <ORISO-Frontend checkout>` (Node 24+).
3. Add the same animals with their avatar file to `guest-name-catalog.json`.
4. Run `GeneratedPseudonymsTest` and `GuestIdentityCatalogParityTest`.

The `Guest identity catalog` workflow checks the copies against ORISO-Frontend `dev` on every pull
request and every night, so a Frontend change that lands without step 2 turns it red.
