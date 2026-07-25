from uuid import UUID

from pydantic import BaseModel, ConfigDict


class WorkflowRef(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    tenant_id: UUID
    workflow_id: UUID
