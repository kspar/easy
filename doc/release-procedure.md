# Release Procedure: YouTrack "In release" Field

When a new version is released, update the "In release" version bundle in YouTrack.

## Setting "In release" on an issue

Whenever an issue moves to **Resolved**, set **In release** at the same time — it records the
version the issue was resolved, the feature shipped, or the bug was fixed in. Always choose the
version carrying the **`- next`** suffix; there is exactly one, and it is the upcoming release.

That's what makes the rename below work: stripping the suffix at release time stamps every issue
resolved during the cycle with the real version number, so nothing has to be revisited
issue-by-issue.

Issues resolved long ago may have the field empty. That's history, not the convention.

## Steps

### 1. Rename the current "- next" version (remove suffix + reset color)

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Y", "color": {"id": "0"}}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values/$CURRENT_NEXT_ID?fields=id,name,color(id)"
```

- `color.id "0"` = default (white/no color)
- Find `$CURRENT_NEXT_ID` by listing values first (see below)

### 2. Add the new "- next" version with green color

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Z - next", "released": false, "archived": false, "color": {"id": "3"}, "$type": "VersionBundleElement"}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values?fields=id,name,color(id)"
```

- `color.id "3"` = green (#1F8039 background, #E3F7E7 foreground)
- Note the returned `id` for the next step

### 3. Move the new version to first position in the dropdown

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Z - next", "ordinal": -100}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values/$NEW_ID?fields=id,name,ordinal"
```

- Use a very low ordinal (e.g. -100) to ensure it appears first

## Reference

- Version bundle ID: `85-2` (name: "easy: In releases")
- List all values: `GET /api/admin/customFieldSettings/bundles/version/85-2?fields=values(id,name,ordinal,color(id))`
- Green color ID: `3`
- Default color ID: `0`
- Base URL: `https://easy.youtrack.cloud`
