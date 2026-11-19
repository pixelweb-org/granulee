import { byteAxis, fmtBytes } from '../lib/format';
import { Chart, type ChartProps } from './Chart';

/** A Chart whose values are bytes. Axis charts are drawn in one binary unit so the ticks are round numbers. */
export function BytesChart(props: Omit<ChartProps, 'format'>) {
  if (props.type === 'pie' || props.type === 'donut') return <Chart {...props} format={fmtBytes} />;
  const max = Math.max(0, ...props.series.flatMap((s) => s.values));
  const axis = byteAxis(max);
  return (
    <Chart
      {...props}
      series={props.series.map((s) => ({ ...s, values: s.values.map((v) => v / axis.divisor) }))}
      format={axis.format}
    />
  );
}
