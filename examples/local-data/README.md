# local-data

Optional, separately built: remote preparation, a short local Jdbi transaction and an atomic
outbox (port 8084). Owns only its demo database; no production authorization or data migration.
Documentation: [manual → Examples → local-data](https://micro.hoori.dev/docs/examples/local-data).

```bash
python3 scripts/optional_example.py build local-data
python3 scripts/test_data.py --hoori-checkout ../hoori
```
