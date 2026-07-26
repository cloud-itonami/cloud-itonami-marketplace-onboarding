# cloud-itonami-marketplace-onboarding

Open Business Blueprint (implemented actor): **self-serve seller
onboarding for a federated marketplace, where a human always signs the
credential.**

This repository publishes a seller-onboarding **coordination** actor —
application intake, eKYC evidence requests, AML screening-result
recording, identity-concern flagging, and *drafting* a seller credential
for human approval — as an OSS business any qualified operator can fork,
deploy, run, improve and sell, so an independent marketplace operator
never surrenders its seller-identity data to a closed onboarding SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime — the same actor pattern as every prior actor in this fleet —
here it is **SellerOnboardingAdvisor ⊣ SellerOnboardingGovernor**.
Contracts come from
[`kotoba-lang/marketplace`](https://github.com/kotoba-lang/marketplace),
which composes [`ekyc`](https://github.com/kotoba-lang/ekyc) and
[`aml`](https://github.com/kotoba-lang/aml). Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn).

> **Why an actor layer at all?** An LLM is good at reading an application
> and drafting an evidence request — but it has no license to conclude
> that an applicant is who they claim to be, no way to independently
> confirm a sanctions screening actually came back clear, and no notion
> of when "draft a credential" quietly becomes "issue one." Letting it
> act directly invites a sanctioned party trading on the platform under
> a credential no human ever looked at. This project seals the
> SellerOnboardingAdvisor into a single node and wraps it with an
> independent **SellerOnboardingGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Why the gate here is *not* "the seller is verified"

Every sibling actor in this fleet hard-blocks on an unverified
counterparty. **This actor cannot** — handling not-yet-verified parties
is its entire job, and inverting that gate naively would make it
useless.

So the gate is displaced onto the one op with lasting consequence:
`:propose-credential`. Intake ops may touch an unverified applicant
freely; drafting the credential that lets them trade cannot proceed
unless the evidence meets the protocol floor.

## Scope: coordination only, never a determination

This actor **never** performs or authorizes:

- completing/finalizing an identity verification
- finalizing a sanctions determination
- issuing a seller credential

`scope-exclusion-violations` re-scans every proposal for this failure
mode independently of the advisor's own framing, and treats it as a
HARD, permanent block regardless of confidence — the same discipline
[ISIC 4791](https://github.com/cloud-itonami/cloud-itonami-isic-4791)
established for fraud determinations.

## Four HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Applicant unknown** | a proposal naming an applicant that does not exist in the store |
| **Evidence floor** | a `:propose-credential` whose applicant lacks a required eKYC check, or is under an AML `:hold`, or was never screened |
| **Effect not `:propose`** | a proposal claiming to directly actuate outside governance |
| **Scope exclusion** | any claim to have concluded identity/sanctions, or issued a credential; plus any op outside the closed allowlist |

The evidence floor is the interesting one. It is recomputed **from the
store's own eKYC session, evidence log and screening results** — never
from the credential the advisor drafted. An advisor that attaches a
flattering evidence summary to its own draft cannot widen its evidence
base that way, and `marketplace.seller/credential-errors` additionally
re-derives the *required* check set from the applicant's kind, so the
advisor cannot lower the floor either. Both directions are asserted in
`advisor-cannot-widen-its-own-evidence-base`.

## A human always signs

`:propose-credential` and `:flag-identity-concern` are absent from
**every** phase's `:auto` set, including phase 3, and the governor marks
them high-stakes independently — two layers, not one. The only path by
which a credential reaches the store runs through `interrupt-before
#{:request-approval}`, so a human signature is structurally unavoidable
rather than merely conventional.

This is the deliberate difference from a fully self-serve marketplace: a
seller can complete their whole application untouched, but no automated
party admits them to trade. ADR-2607264000 records that as the cost of
operating in AML/sanctions jurisdictions, not an unfinished feature.

```bash
clojure -M:dev:run   # one clean intake, one HARD hold, one human-gated credential
clojure -M:test      # 31 tests, 115 assertions
clojure -M:lint
```

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-intake | `:log-application` | — |
| 2 assisted-evidence | + `:request-evidence` `:record-screening` | — |
| 3 supervised-auto | all | `:log-application` `:request-evidence` `:record-screening` |

`:propose-credential` and `:flag-identity-concern` never appear in the
right-hand column, at any phase. `phase-test` asserts this across every
phase entry so a phase added later cannot quietly change it.
