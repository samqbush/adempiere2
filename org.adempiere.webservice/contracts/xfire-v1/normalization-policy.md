# XFire v1 contract normalization policy

The files in this directory are a self-frozen oracle captured from the
unmodified Phase 3 installed product. `manifest.sha256` protects every captured
WSDL, request, response, header block, round-trip result, and operation record.

No SOAP payload field, namespace, element order, fault, HTTP status, content
type, or charset is normalized. The volatile `Date` response header and
`JSESSIONID` cookie value are replaced with `<DATE>` and `<SESSION>`; their
presence, cookie attributes, and all other headers remain exact. Tomcat logs are
excluded. WSDL endpoint addresses remain exactly as captured and are compared
separately from the two required public compatibility URL forms.

Updating the oracle requires a successful `phase4OraclePreflight`, explicit
human review of the generated evidence, and
`freezePhase4XFireContracts -PapprovePhase4Oracle=true`. Unapproved additions,
removals, or byte changes fail `verifyPhase4FrozenContracts`.
