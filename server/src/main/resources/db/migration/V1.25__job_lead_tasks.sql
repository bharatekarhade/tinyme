-- The task a job became when the owner tapped "Add to tasks". No foreign key: deleting the task keeps the job as it is.
ALTER TABLE job_leads ADD COLUMN plan_id uuid;
