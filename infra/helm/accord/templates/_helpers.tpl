{{- define "accord.name" -}}
accord
{{- end -}}

{{- define "accord.componentNames" -}}
{{ list "control-api" "control-worker" "webhook-edge" | toJson }}
{{- end -}}

{{- define "accord.commonLabels" -}}
app.kubernetes.io/part-of: accord
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
accord.inforvans.com/environment-class: {{ .Values.environment_class | quote }}
{{- end -}}

{{- define "accord.componentLabels" -}}
{{ include "accord.commonLabels" .root }}
app.kubernetes.io/name: {{ .name | quote }}
app.kubernetes.io/instance: {{ printf "%s-%s" .root.Release.Name .name | quote }}
accord.inforvans.com/component: {{ .name | quote }}
accord.inforvans.com/network-profile: {{ .component.network.profile | quote }}
{{- end -}}

{{- define "accord.contractAnnotations" -}}
accord.inforvans.com/identity-sha256: {{ .Values.contract.identity_sha256 | quote }}
accord.inforvans.com/promotion-sha256: {{ .Values.contract.promotion_sha256 | quote }}
accord.inforvans.com/release-manifest-digest: {{ .Values.contract.release_manifest_digest | quote }}
accord.inforvans.com/verification-receipt-digest: {{ .Values.contract.verification_receipt_digest | quote }}
accord.inforvans.com/remote-sha: {{ .Values.contract.remote_sha | quote }}
{{- end -}}

{{- define "accord.identityAnnotations" -}}
{{- if eq .provider "AWS" }}
eks.amazonaws.com/role-arn: {{ .role_arn | quote }}
{{- else if eq .provider "GCP" }}
iam.gke.io/gcp-service-account: {{ .service_account_email | quote }}
{{- else if eq .provider "AZURE" }}
azure.workload.identity/client-id: {{ .client_id | quote }}
azure.workload.identity/tenant-id: {{ .tenant_id | quote }}
{{- else if ne .provider "KUBERNETES" }}
{{- fail (printf "unsupported closed identity provider %s" .provider) }}
{{- end }}
{{- end -}}

{{- define "accord.selectorMatchLabels" -}}
{{ .key }}: {{ .value | quote }}
{{- end -}}
