export const devFeedbackProjects = ["afterchime", "decree", "please-just-sleep"] as const;

export type DevFeedbackProject = (typeof devFeedbackProjects)[number];

export type DevFeedbackProjectConfig = Readonly<{
  adminKeyHash: string;
  clientKeyHash: string | null;
  deploymentUrl: string | null;
}>;

export const trustedProjectIdentities: Readonly<
  Record<Exclude<DevFeedbackProject, "afterchime">, { installationId: string; userId: string }>
> = {
  decree: {
    installationId: "10000000-0000-4000-8000-000000000002",
    userId: "10000000-0000-4000-8000-000000000001",
  },
  "please-just-sleep": {
    installationId: "20000000-0000-4000-8000-000000000002",
    userId: "20000000-0000-4000-8000-000000000001",
  },
};

export function isDevFeedbackProject(value: unknown): value is DevFeedbackProject {
  return typeof value === "string" && devFeedbackProjects.includes(value as DevFeedbackProject);
}
