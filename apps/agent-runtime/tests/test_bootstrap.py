from uuid import UUID

import pytest
from pydantic import ValidationError
from temporalio import workflow

from accord_agent_runtime.boundary import WorkflowRef


def test_runtime_dependencies_and_identifier_only_boundary() -> None:
    reference = WorkflowRef(
        tenant_id=UUID("10000000-0000-0000-0000-000000000001"),
        workflow_id=UUID("40000000-0000-0000-0000-000000000001"),
    )

    assert callable(workflow.defn)
    assert set(WorkflowRef.model_fields) == {"tenant_id", "workflow_id"}
    with pytest.raises(ValidationError):
        WorkflowRef.model_validate(
            {
                "tenant_id": reference.tenant_id,
                "workflow_id": reference.workflow_id,
                "source_code": "customer source must not enter workflow payloads",
            }
        )
