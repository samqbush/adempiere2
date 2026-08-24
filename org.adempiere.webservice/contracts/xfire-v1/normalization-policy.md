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

`operation-known-residuals.tsv` is not successful dispatch evidence.
`ExternalSales.uploadOrders` has a pre-existing RPC binding defect caused by
its WSDL message referencing a nonexistent global `ArrayOf_tns1_Order` element.
The exact legacy fault is frozen so the compatibility layer cannot silently
change it; any contract repair requires a separately versioned interface after
consumer review.

Updating the oracle requires a successful `phase4OraclePreflight`, explicit
human review of the generated evidence, and
`freezePhase4XFireContracts -PapprovePhase4Oracle=true`. Unapproved additions,
removals, or byte changes fail `verifyPhase4FrozenContracts`.
