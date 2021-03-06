import { ChangeDetectionStrategy, Component, Input, OnChanges } from '@angular/core';
import { Instance, Parameter, ParameterValue, Value } from '../../client';
import { BehaviorSubject } from 'rxjs';

@Component({
  selector: 'app-parameter-detail',
  templateUrl: './ParameterDetail.html',
  styleUrls: ['./ParameterDetail.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParameterDetail implements OnChanges {

  @Input()
  instance: Instance;

  @Input()
  parameter: Parameter;

  @Input()
  pval: ParameterValue;

  showRaw$ = new BehaviorSubject<boolean>(false);
  value$ = new BehaviorSubject<Value | null>(null);

  ngOnChanges() {
    if (this.pval) {
      if (this.showRaw$.value) {
        this.value$.next(this.pval.rawValue);
      } else {
        this.value$.next(this.pval.engValue);
      }
    }
  }

  showRawValue() {
    this.showRaw$.next(true);
    if (this.pval) {
      this.value$.next(this.pval.rawValue);
    }
  }

  showEngineeringValue() {
    this.showRaw$.next(false);
    if (this.pval) {
      this.value$.next(this.pval.engValue);
    }
  }
}
