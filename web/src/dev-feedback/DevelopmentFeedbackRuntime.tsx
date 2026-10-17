import { useEffect, useState } from "react";
import type { NativeResponse } from "../bridge";
import { DevFeedbackTool } from "./DevFeedbackTool";
import { supportsDevFeedbackClose } from "./model";
import { NativeDevFeedbackTransport } from "./NativeDevFeedbackTransport";

export type DevFeedbackResponseSink = (response: NativeResponse) => void;

type DevelopmentFeedbackRuntimeProps = Readonly<{
  route: string;
  shellRevision: string;
  appVersion: string;
  registerResponseSink(sink: DevFeedbackResponseSink | null): void;
}>;

export default function DevelopmentFeedbackRuntime({
  route,
  shellRevision,
  appVersion,
  registerResponseSink,
}: DevelopmentFeedbackRuntimeProps) {
  const [transport] = useState(() => new NativeDevFeedbackTransport());

  useEffect(() => {
    registerResponseSink((response) => transport.handleResponse(response));
    return () => registerResponseSink(null);
  }, [registerResponseSink, transport]);

  return (
    <DevFeedbackTool
      route={route}
      shellRevision={shellRevision}
      appVersion={appVersion}
      canCloseItems={supportsDevFeedbackClose(appVersion)}
      transport={transport}
    />
  );
}
